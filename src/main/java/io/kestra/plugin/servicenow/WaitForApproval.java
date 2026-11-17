package io.kestra.plugin.servicenow;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@Schema(title = "Wait for a ServiceNow change approval", description = """
    Polls the Change Management API until the change's approval field is approved or rejected.
    Rejection fails by default. Timeout always fails; the inherited task timeout defaults internally
    to 3 days and is capped at 7 days. Cancellation stops polling without approving the change.
    Reads approver audit details from sysapproval_approver, then re-reads approval before returning.
    The instance owns approval rounds. Set approvedState when its workflow exposes approved before
    reaching the state that your downstream operation requires. Audit reads need their own table ACLs.
    """)
@Plugin(examples = @Example(title = "Wait for approval before a downstream operation", full = true, code = """
    id: servicenow_wait_approval
    namespace: company.operations
    inputs:
      - id: change_sys_id
        type: STRING
    tasks:
      - id: approval
        type: io.kestra.plugin.servicenow.WaitForApproval
        domain: "{{ secret('SNOW_DOMAIN') }}"
        username: "{{ secret('SNOW_USERNAME') }}"
        password: "{{ secret('SNOW_PASSWORD') }}"
        sysId: "{{ inputs.change_sys_id }}"
        timeout: P3D
        interval: PT30S
        fetchType: STORE
    """))
public class WaitForApproval extends AbstractServiceNowPollingTask implements RunnableTask<WaitForApproval.Output> {
    public enum Approval {
        APPROVED,
        REJECTED
    }

    @NotNull
    @Schema(title = "Change sys_id")
    @PluginProperty(group = "main")
    private Property<String> sysId;
    @Builder.Default
    @Schema(title = "Polling interval", description = "Positive duration, at most 5 minutes. Defaults to 30 seconds.", defaultValue = "PT30S")
    @PluginProperty(group = "reliability")
    private Property<Duration> interval = Property.ofValue(Duration.ofSeconds(30));
    @Builder.Default
    @Schema(title = "Fail on rejection", description = "True by default. False returns REJECTED with the audit details.", defaultValue = "true")
    @PluginProperty(group = "reliability")
    private Property<Boolean> failOnRejection = Property.ofValue(true);
    @Schema(title = "Required approved state", description = "Optional state value or display label that must accompany approval=approved. Configure for the instance's workflow.")
    @PluginProperty(group = "advanced")
    private Property<String> approvedState;
    @Builder.Default
    @Schema(title = "Maximum approver records", description = "Between 1 and 10000, default 1000. Exceeding the limit fails rather than truncating the audit.", defaultValue = "1000")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxApprovers = Property.ofValue(1000);
    @Builder.Default
    @Schema(title = "Fetch type", description = "FETCH returns the bounded approver list, FETCH_ONE its first row, STORE an ION URI and NONE only the count.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext context) throws Exception {
        try (var poll = begin(context, Duration.ofDays(3), Duration.ofDays(7))) {
            var rId = ServiceNowValues.sysId(context.render(sysId).as(String.class).orElse(null), "sysId");
            var rInterval = ServiceNowValues.duration(context.render(interval).as(Duration.class).orElse(null), Duration.ofSeconds(30), "interval", Duration.ofMinutes(5));
            var rFail = context.render(failOnRejection).as(Boolean.class).orElse(true);
            var rState = context.render(approvedState).as(String.class).orElse(null);
            var rMaximum = context.render(maxApprovers).as(Integer.class).orElse(1000);
            var rFetch = context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);
            var api = api(context, poll);
            while (true) {
                var record = ServiceNowRecords.change(api, rId);
                var approval = approval(record);
                if (approval == null || approval == Approval.APPROVED && rState != null && !ServiceNowRecords.isState(record, rState)) {
                    poll.pause(rInterval);
                    continue;
                }
                var audit = ServiceNowRecords.approvals(api, rId, rMaximum);
                if (audit.isEmpty()) {
                    throw new IllegalStateException("ServiceNow approval has no readable audit records. Check sysapproval_approver ACLs and the change model before proceeding.");
                }
                var fresh = ServiceNowRecords.change(api, rId);
                var confirmed = approval(fresh);
                if (confirmed != approval || approval == Approval.APPROVED && rState != null && !ServiceNowRecords.isState(fresh, rState)) {
                    poll.pause(rInterval);
                    continue;
                }
                URI uri = null;
                if (rFetch == FetchType.STORE || approval == Approval.REJECTED && rFail) {
                    var file = context.workingDir().createTempFile(".ion");
                    try {
                        try (var output = java.nio.file.Files.newOutputStream(file)) {
                            for (var row : audit) {
                                FileSerde.write(output, row);
                            }
                        }
                        uri = context.storage().putFile(file.toFile());
                    } finally {
                        java.nio.file.Files.deleteIfExists(file);
                    }
                }
                if (approval == Approval.REJECTED && rFail) {
                    throw new IllegalStateException("ServiceNow change %s was rejected. Approver audit: %s. Review the comments and request approval again.".formatted(rId, uri));
                }
                poll.check();
                context.logger().info("ServiceNow change {} approval is {}", rId, approval);
                return Output.builder().approval(approval).state(ServiceNowValues.field(fresh, "state"))
                    .stateLabel(fresh.path("state").path("display_value").asText(null)).approvers(rFetch == FetchType.FETCH ? audit : null)
                    .approver(rFetch == FetchType.FETCH_ONE ? audit.getFirst() : null).uri(rFetch == FetchType.STORE ? uri : null).size(audit.size()).build();
            }
        }
    }

    private Approval approval(com.fasterxml.jackson.databind.JsonNode record) {
        if (ServiceNowRecords.isCancelled(record)) {
            throw new IllegalStateException("ServiceNow change was cancelled. Stop and check the change before proceeding.");
        }
        var value = ServiceNowRecords.normaliseState(ServiceNowValues.field(record, "approval"));
        return switch (value) {
            case "approved" -> Approval.APPROVED;
            case "rejected" -> Approval.REJECTED;
            case "requested" -> null;
            case "not requested", "not required" -> throw new IllegalStateException("ServiceNow change has not requested approval. Request approval through its model before waiting.");
            case "cancelled", "canceled" -> throw new IllegalStateException("ServiceNow change approval was cancelled. Stop and check the change before proceeding.");
            default -> throw new IllegalStateException("ServiceNow returned an unknown or missing approval value. Check the Change API and approval model.");
        };
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Confirmed approval")
        private final Approval approval;
        @Schema(title = "Server state value")
        private final String state;
        @Schema(title = "Server state label")
        private final String stateLabel;
        @Schema(title = "Approver audit records", description = "Present with FETCH. Includes historical and pending records as returned by the instance.")
        private final List<ApprovalRecord> approvers;
        @Schema(title = "First approver audit record", description = "Present with FETCH_ONE.")
        private final ApprovalRecord approver;
        @Schema(title = "ION audit URI", description = "Present with STORE. Rejection failures include the audit URI in the error.")
        private final URI uri;
        @Schema(title = "Number of audit records")
        private final Integer size;
    }
}
