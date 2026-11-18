package io.kestra.plugin.servicenow;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.annotations.TicketingField;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.TicketingTaskInterface;
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
@Schema(title = "Create a ServiceNow change request", description = """
    Creates NORMAL or EMERGENCY changes through the JSON-capable Change Management API, or a STANDARD
    change from a published template. Requested fields are read back before returning.
    STANDARD overrides use query parameters, with a 2048-byte complete encoded URL ceiling.
    Query text may appear in access logs; do not include secrets. STANDARD descriptions and plans are template-owned.
    requestApproval resolves approvalState against the instance's nextstates, requests that transition
    and waits for generated approval records. It never marks a change approved itself.
    Human references need read access to their reference tables; sys_ids avoid those lookups.
    Change reads and writes do not use the Table API. Approval setup additionally needs read access to
    sysapproval_approver. The internal polling budget defaults to 30 seconds and is capped at 5 minutes.
    A larger core timeout is accepted but does not extend this internal budget.
    Creation is not idempotent. On a partial failure, check the reported change sys_id before retrying.
    """)
@Plugin(examples = @Example(title = "Open a normal change and request approval", full = true, code = """
    id: servicenow_request_change
    namespace: company.operations
    tasks:
      - id: change
        type: io.kestra.plugin.servicenow.CreateChange
        domain: "{{ secret('SNOW_DOMAIN') }}"
        username: "{{ secret('SNOW_USERNAME') }}"
        password: "{{ secret('SNOW_PASSWORD') }}"
        model: NORMAL
        shortDescription: Update web-01 inventory
        assignmentGroup: Change Management
        implementationPlan: Import approved inventory through the transform map
        requestApproval: true
    """))
public class CreateChange extends AbstractServiceNowPollingTask implements RunnableTask<CreateChange.Output>, TicketingTaskInterface {
    private static final int MAX_STANDARD_URL_BYTES = 2048;
    private static final Set<String> STANDARD_TEMPLATE_FIELDS = Set.of("description", "backout_plan", "test_plan", "implementation_plan");

    public enum Model {
        NORMAL,
        STANDARD,
        EMERGENCY
    }

    @Builder.Default
    @Schema(title = "Change type", description = """
        NORMAL by default. STANDARD requires standardTemplate.
        """, defaultValue = "NORMAL")
    @PluginProperty(group = "main")
    private Property<Model> model = Property.ofValue(Model.NORMAL);
    @Schema(title = "Standard template sys_id", description = """
        Required for STANDARD; identifies a published standard change template.
        Overrides use query parameters with a 2048-byte encoded URL ceiling. The template supplies descriptions and plans.
        """)
    @PluginProperty(group = "main")
    private Property<String> standardTemplate;
    @Schema(title = "Change model", description = """
        Optional chg_model name for NORMAL or EMERGENCY. The server owns model behavior.
        """)
    @PluginProperty(group = "main")
    private Property<String> changeModel;
    @NotNull
    @Schema(title = "Short description")
    @PluginProperty(group = "main")
    @TicketingField(role = TicketingField.Role.CASE_TITLE)
    private Property<String> shortDescription;
    @Schema(title = "Change description")
    @PluginProperty(group = "main")
    @TicketingField(role = TicketingField.Role.CASE_DESCRIPTION)
    private Property<String> changeDescription;
    @Schema(title = "Assignment group", description = """
        Group name or sys_id. Required when requestApproval is true.
        """)
    @PluginProperty(group = "main")
    private Property<String> assignmentGroup;
    @Schema(title = "Configuration item", description = """
        CI name or sys_id. Ambiguous names fail before creation.
        """)
    @PluginProperty(group = "main")
    private Property<String> configurationItem;
    @Schema(title = "Justification")
    @PluginProperty(group = "main")
    private Property<String> justification;
    @Schema(title = "Implementation plan")
    @PluginProperty(group = "main")
    private Property<String> implementationPlan;
    @Schema(title = "Backout plan")
    @PluginProperty(group = "main")
    private Property<String> backoutPlan;
    @Schema(title = "Test plan")
    @PluginProperty(group = "main")
    private Property<String> testPlan;
    @Schema(title = "Planned start", description = """
        ISO timestamp with offset or yyyy-MM-dd HH:mm:ss in UTC. Set with plannedEnd.
        """)
    @PluginProperty(group = "main")
    private Property<String> plannedStart;
    @Schema(title = "Planned end", description = """
        Must be later than plannedStart. Both are converted to UTC.
        """)
    @PluginProperty(group = "main")
    private Property<String> plannedEnd;
    @Schema(title = "Additional change fields", description = """
        Scalar API fields. Typed inputs take precedence. sys_id, state and approval cannot be supplied.
        """)
    @PluginProperty(group = "advanced")
    @ToString.Exclude
    private Property<Map<String, Object>> data;
    @Builder.Default
    @Schema(title = "Request approval", description = """
        False by default. Requests the configured model transition and verifies approval records were generated.
        """, defaultValue = "false")
    @PluginProperty(group = "main")
    private Property<Boolean> requestApproval = Property.ofValue(false);
    @Builder.Default
    @Schema(title = "Approval request state", description = """
        State value or label, Assess by default. Configure for custom models.
        """, defaultValue = "Assess")
    @PluginProperty(group = "advanced")
    private Property<String> approvalState = Property.ofValue("Assess");

    @Override
    public Output run(RunContext context) throws Exception {
        try (var poll = begin(context, Duration.ofSeconds(30), Duration.ofMinutes(5))) {
            var rModel = context.render(model).as(Model.class).orElse(Model.NORMAL);
            var rTemplate = context.render(standardTemplate).as(String.class).orElse(null);
            var rChangeModel = context.render(changeModel).as(String.class).orElse(null);
            if (rModel == Model.STANDARD) {
                rTemplate = ServiceNowValues.sysId(rTemplate, "standardTemplate");
                if (rChangeModel != null) {
                    throw new IllegalArgumentException("changeModel cannot override a STANDARD template's model.");
                }
            } else if (rTemplate != null) {
                throw new IllegalArgumentException("standardTemplate is only valid for STANDARD changes.");
            }
            var body = fields(context);
            if (rModel == Model.STANDARD && body.containsKey("chg_model")) {
                throw new IllegalArgumentException("STANDARD data cannot override its published template's chg_model.");
            }
            if (rModel == Model.STANDARD && body.keySet().stream().anyMatch(STANDARD_TEMPLATE_FIELDS::contains)) {
                throw new IllegalArgumentException("STANDARD description and plans come from the published template and cannot be overridden. Remove those inputs or use NORMAL/EMERGENCY.");
            }
            var rRequest = context.render(requestApproval).as(Boolean.class).orElse(false);
            var rGroup = context.render(assignmentGroup).as(String.class).orElse(null);
            if (rRequest) {
                ServiceNowValues.required(rGroup, "assignmentGroup");
            }
            var api = api(context, poll);
            if (rGroup != null) {
                body.put("assignment_group", ServiceNowRecords.reference(api, "sys_user_group", rGroup, List.of("name")));
            }
            var rCi = context.render(configurationItem).as(String.class).orElse(null);
            if (rCi != null) {
                body.put("cmdb_ci", ServiceNowRecords.reference(api, "cmdb_ci", rCi, List.of("name")));
            }
            body.put("type", rModel.name().toLowerCase(Locale.ROOT));
            var expected = new LinkedHashMap<>(body);
            var modelIdentifier = rChangeModel != null ? rChangeModel : body.get("chg_model");
            if (modelIdentifier != null) {
                var resolved = ServiceNowRecords.changeModel(api, modelIdentifier);
                body.put("chg_model", resolved.name());
                expected.put("chg_model", resolved.sysId());
            }
            ServiceNowValues.stringValues(new LinkedHashMap<>(body));
            var path = rModel == Model.STANDARD ? "api/sn_chg_rest/change/standard/%s".formatted(rTemplate) : "api/sn_chg_rest/change";
            if (rModel == Model.STANDARD && api.requestUri(path, body).toASCIIString().length() > MAX_STANDARD_URL_BYTES) {
                throw new IllegalArgumentException(
                    "STANDARD creation URL exceeds %d encoded bytes. Shorten the overrides or use NORMAL/EMERGENCY for long content.".formatted(MAX_STANDARD_URL_BYTES)
                );
            }
            var created = ServiceNowValues.record(
                rModel == Model.STANDARD
                    ? api.post(path, body, null)
                    : api.post(path, Map.of(), body)
            );
            var id = ServiceNowValues.recordId(created);
            try {
                ServiceNowValues.ignoredFields(created);
                var record = ServiceNowRecords.change(api, id);
                ServiceNowValues.verify(record, expected);
                if (rRequest) {
                    var rState = ServiceNowValues.required(context.render(approvalState).as(String.class).orElse("Assess"), "approvalState");
                    var next = ServiceNowValues.record(api.get("api/sn_chg_rest/change/%s/nextstates".formatted(id), Map.of()));
                    var state = ServiceNowRecords.stateId(next, rState);
                    ServiceNowRecords.allowed(next, ServiceNowValues.required(ServiceNowValues.field(record, "state"), "change state"), state);
                    ServiceNowValues.record(api.patch("api/sn_chg_rest/change/%s".formatted(id), Map.of("state", state)));
                    record = ServiceNowRecords.change(api, id);
                    if (!state.equals(ServiceNowValues.field(record, "state"))) {
                        throw new IllegalStateException("ServiceNow did not reach approvalState. Check the model transition and instance rules.");
                    }
                    while (ServiceNowRecords.approvals(api, id, 1000).isEmpty()) {
                        poll.pause(Duration.ofMillis(250));
                    }
                }
                poll.check();
                context.logger().info("Created ServiceNow change {}", id);
                return Output.builder().sysId(id).number(ServiceNowValues.field(record, "number")).url(api.recordUrl("change_request", id))
                    .state(ServiceNowValues.field(record, "state")).stateLabel(record.path("state").path("display_value").asText(null))
                    .approval(ServiceNowValues.field(record, "approval")).build();
            } catch (CancellationException e) {
                throw new CancellationException("ServiceNow change %s was created before cancellation. Check it before retrying.".formatted(id));
            } catch (Exception e) {
                throw new IllegalStateException("ServiceNow change %s was created but setup did not complete. Check it before retrying. %s".formatted(id, e.getMessage()), e);
            }
        }
    }

    private Map<String, String> fields(RunContext context) throws Exception {
        var rData = context.render(data).asMap(String.class, Object.class);
        if (rData.keySet().stream().anyMatch(Set.of("sys_id", "state", "approval", "sys_mod_count")::contains)) {
            throw new IllegalArgumentException("ServiceNow data cannot set sys_id, state, approval or sys_mod_count. Use the model-driven tasks.");
        }
        var result = new LinkedHashMap<String, String>(rData.isEmpty() ? Map.of() : ServiceNowValues.stringValues(rData));
        result.put("short_description", ServiceNowValues.required(context.render(shortDescription).as(String.class).orElse(null), "shortDescription"));
        put(context, result, "description", changeDescription);
        put(context, result, "justification", justification);
        put(context, result, "implementation_plan", implementationPlan);
        put(context, result, "backout_plan", backoutPlan);
        put(context, result, "test_plan", testPlan);
        var rStart = ServiceNowValues.date(context.render(plannedStart).as(String.class).orElse(null), "plannedStart");
        var rEnd = ServiceNowValues.date(context.render(plannedEnd).as(String.class).orElse(null), "plannedEnd");
        if ((rStart == null) != (rEnd == null) || rStart != null && rEnd.compareTo(rStart) <= 0) {
            throw new IllegalArgumentException("ServiceNow plannedStart and plannedEnd must be supplied together, with end later than start.");
        }
        if (rStart != null) {
            result.put("start_date", rStart);
            result.put("end_date", rEnd);
        }
        return result;
    }

    private static void put(RunContext context, Map<String, String> fields, String key, Property<String> property) throws Exception {
        context.render(property).as(String.class).ifPresent(rValue ->
        {
            if (rValue.length() > ServiceNowValues.MAX_TEXT) {
                throw new IllegalArgumentException("ServiceNow change field exceeds 65536 characters. Shorten its text.");
            }
            fields.put(key, rValue);
        });
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Change sys_id")
        private final String sysId;
        @Schema(title = "Change number")
        @TicketingField(role = TicketingField.Role.TICKET_KEY)
        private final String number;
        @Schema(title = "Change UI URL")
        @TicketingField(role = TicketingField.Role.TICKET_URL)
        private final String url;
        @Schema(title = "Server state value")
        private final String state;
        @Schema(title = "Server state label")
        private final String stateLabel;
        @Schema(title = "Server approval value")
        private final String approval;
    }
}
