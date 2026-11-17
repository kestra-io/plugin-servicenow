package io.kestra.plugin.servicenow;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class ApprovalRecord {
    @Schema(title = "Approval record sys_id")
    private final String sysId;
    @Schema(title = "Approver name")
    private final String name;
    @Schema(title = "Approver email")
    private final String email;
    @Schema(title = "Approval record state", description = "The state returned by sysapproval_approver, including pending or cancelled records.")
    private final String state;
    @Schema(title = "Approver comments")
    private final String comments;
    @Schema(title = "Record update time", description = "sys_updated_on in UTC. This is the last record update, not a guaranteed decision-event timestamp.")
    private final Instant updatedAt;
}
