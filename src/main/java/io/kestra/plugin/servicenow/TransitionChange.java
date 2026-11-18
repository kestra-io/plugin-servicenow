package io.kestra.plugin.servicenow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Schema(title = "Transition a ServiceNow change through its model", description = """
    Resolves each requested state against fresh nextstates and checks its model conditions before PATCH.
    Closing defaults to Scheduled, Implement and Review waypoints; set via for a custom model or []
    for a direct transition. These are requested waypoints, not a locally invented model graph.
    State and revision are re-read immediately before each write. State, closeCode and closeNotes are
    verified after the transition. Work notes are sent only with the final transition and are not
    appended again when the change already reached the target. Native journal readback is not claimed.
    A refused or ineffective transition fails with its model conditions. No Table API change writes
    are used. The internal polling budget defaults to 5 minutes and is capped at 1 hour, with at most 32 waypoints.
    """)
@Plugin(examples = @Example(title = "Close an approved change", full = true, code = """
    id: servicenow_close_change
    namespace: company.operations
    inputs:
      - id: change_sys_id
        type: STRING
    tasks:
      - id: close
        type: io.kestra.plugin.servicenow.TransitionChange
        domain: "{{ secret('SNOW_DOMAIN') }}"
        username: "{{ secret('SNOW_USERNAME') }}"
        password: "{{ secret('SNOW_PASSWORD') }}"
        sysId: "{{ inputs.change_sys_id }}"
        to: Closed
        closeCode: successful
        closeNotes: Inventory import completed and was verified
    """))
public class TransitionChange extends AbstractServiceNowPollingTask implements RunnableTask<TransitionChange.Output> {
    @NotNull
    @Schema(title = "Change sys_id")
    @PluginProperty(group = "main")
    private Property<String> sysId;
    @NotNull
    @Schema(title = "Target state", description = """
        Instance state value or display label. Values such as -4 and 4 remain distinct.
        """)
    @PluginProperty(group = "main")
    private Property<String> to;
    @Schema(
        title = "Ordered intermediate states",
        description = """
            Optional state labels/values. Closed defaults to [Scheduled, Implement, Review].
            [] requests a direct transition. Maximum 31 intermediate states.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<List<String>> via;
    @Schema(title = "Close code", description = """
        Required for Closed. Use the instance's API choice value, for example successful.
        """)
    @PluginProperty(group = "main")
    private Property<String> closeCode;
    @Schema(title = "Close notes", description = """
        Required for Closed.
        """)
    @PluginProperty(group = "main")
    private Property<String> closeNotes;
    @Schema(title = "Work notes", description = """
        Optional journal entry sent once with the final state transition.
        """)
    @PluginProperty(group = "main")
    @ToString.Exclude
    private Property<String> workNotes;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var poll = begin(context, Duration.ofMinutes(5), Duration.ofHours(1))) {
            var rId = ServiceNowValues.sysId(context.render(sysId).as(String.class).orElse(null), "sysId");
            var rTo = ServiceNowValues.required(context.render(to).as(String.class).orElse(null), "to");
            var finalFields = new LinkedHashMap<String, String>();
            var rCode = context.render(closeCode).as(String.class).orElse(null);
            var rNotes = context.render(closeNotes).as(String.class).orElse(null);
            var closing = "closed".equals(ServiceNowRecords.normaliseState(rTo)) || rCode != null || rNotes != null;
            if (closing) {
                finalFields.put("close_code", ServiceNowValues.required(rCode, "closeCode"));
                finalFields.put("close_notes", ServiceNowValues.required(rNotes, "closeNotes"));
            }
            var rWork = context.render(workNotes).as(String.class).orElse(null);
            if (rWork != null && rWork.length() > ServiceNowValues.MAX_TEXT) {
                throw new IllegalArgumentException("ServiceNow workNotes exceeds 65536 characters. Shorten the journal entry.");
            }
            List<String> rVia = via == null ? closing ? List.of("Scheduled", "Implement", "Review") : List.of() : context.render(via).asList(String.class);
            var route = new ArrayList<>(rVia);
            route.add(rTo);
            if (
                route.size() > 32 || route.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 80)
                    || route.stream().map(ServiceNowRecords::normaliseState).distinct().count() != route.size()
            ) {
                throw new IllegalArgumentException("ServiceNow via/to must contain at most 32 distinct non-blank state values or labels, at most 80 characters each.");
            }
            var api = api(context, poll);
            var record = ServiceNowRecords.change(api, rId);
            var next = nextstates(api, rId);
            var start = startIndex(record, next, route);
            var history = new ArrayList<Step>();
            var seen = new HashSet<String>();
            for (var index = start; index < route.size(); index++) {
                poll.check();
                record = ServiceNowRecords.change(api, rId);
                next = nextstates(api, rId);
                var from = ServiceNowValues.required(ServiceNowValues.field(record, "state"), "change state");
                var target = ServiceNowRecords.stateId(next, route.get(index));
                if (from.equals(target)) {
                    continue;
                }
                if (!seen.add(from)) {
                    throw new IllegalStateException("ServiceNow change revisited a prior state. Stop and inspect concurrent changes/model cycles before retrying.");
                }
                ServiceNowRecords.allowed(next, from, target);
                var fresh = ServiceNowRecords.change(api, rId);
                if (!from.equals(ServiceNowValues.field(fresh, "state")) || !sameRevision(record, fresh)) {
                    throw new IllegalStateException("ServiceNow change changed while planning the transition. Re-read it and retry with its current model state.");
                }
                var body = new LinkedHashMap<String, String>();
                body.put("state", target);
                if (index == route.size() - 1) {
                    body.putAll(finalFields);
                    if (rWork != null) {
                        body.put("work_notes", rWork);
                    }
                }
                try {
                    ServiceNowValues.ignoredFields(ServiceNowValues.record(api.patch("api/sn_chg_rest/change/%s".formatted(rId), body)));
                } catch (ServiceNowApi.ApiException e) {
                    if (e.status() == 400) {
                        var current = nextstates(api, rId);
                        try {
                            ServiceNowRecords.allowed(current, from, target);
                        } catch (IllegalStateException modelFailure) {
                            throw new IllegalStateException("ServiceNow refused the transition. %s %s".formatted(e.getMessage(), modelFailure.getMessage()), e);
                        }
                    }
                    throw e;
                }
                record = ServiceNowRecords.change(api, rId);
                if (!target.equals(ServiceNowValues.field(record, "state"))) {
                    throw new IllegalStateException("ServiceNow did not reach requested state %s. Stop and inspect the current change/model before retrying.".formatted(route.get(index)));
                }
                history.add(Step.builder().from(from).to(target).build());
            }
            if (
                !ServiceNowRecords.isState(record, rTo)
                    && !ServiceNowRecords.normaliseState(rTo).equals(ServiceNowRecords.normaliseState(next.path("state_label").path(ServiceNowValues.field(record, "state")).asText()))
            ) {
                throw new IllegalStateException("ServiceNow change did not reach the requested final state. Check the model and via values.");
            }
            ServiceNowValues.verify(record, finalFields);
            poll.check();
            context.logger().info("ServiceNow change {} reached state {} through {} writes", rId, ServiceNowValues.field(record, "state"), history.size());
            return Output.builder().state(ServiceNowValues.field(record, "state")).stateLabel(record.path("state").path("display_value").asText(null))
                .changed(!history.isEmpty()).transitions(history).build();
        }
    }

    private JsonNode nextstates(ServiceNowApi api, String id) throws Exception {
        return ServiceNowValues.record(api.get("api/sn_chg_rest/change/%s/nextstates".formatted(id), Map.of()));
    }

    private int startIndex(JsonNode record, JsonNode next, List<String> route) {
        var label = next.path("state_label").path(ServiceNowValues.field(record, "state")).asText();
        for (var index = route.size() - 1; index >= 0; index--) {
            if (ServiceNowRecords.isState(record, route.get(index)) || ServiceNowRecords.normaliseState(route.get(index)).equals(ServiceNowRecords.normaliseState(label))) {
                return index + 1;
            }
        }
        return 0;
    }

    private boolean sameRevision(JsonNode before, JsonNode after) {
        var revision = ServiceNowValues.field(before, "sys_mod_count");
        return (revision == null || revision.equals(ServiceNowValues.field(after, "sys_mod_count")))
            && Objects.equals(ServiceNowValues.field(before, "approval"), ServiceNowValues.field(after, "approval"));
    }

    @Builder
    @Getter
    public static class Step {
        @Schema(title = "Prior server state")
        private final String from;
        @Schema(title = "Verified server state")
        private final String to;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Verified final server state")
        private final String state;
        @Schema(title = "Server state label")
        private final String stateLabel;
        @Schema(title = "State was changed", description = """
            False when the requested target was already reached. No work-note entry is appended in that case.
            """)
        private final Boolean changed;
        @Schema(title = "Verified transition writes")
        private final List<Step> transitions;
    }
}
