package io.kestra.plugin.servicenow;

import java.util.Map;

import io.kestra.core.models.annotations.TicketingField;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class CreatedRecord implements io.kestra.core.models.tasks.Output {
    @Schema(
        title = "Created record",
        description = "ServiceNow response for the inserted row."
    )
    private Map<String, Object> result;

    @Schema(
        title = "Record number",
        description = "Human-readable number of the created record, such as `INC0010002`. Empty for tables that have no `number` field."
    )
    @TicketingField(role = TicketingField.Role.TICKET_KEY)
    private String number;

    @Schema(
        title = "Record sys_id",
        description = "Unique identifier of the created record; pass it to `Update`, `Get` or `Delete`."
    )
    private String sysId;

    @Schema(
        title = "Record URL",
        description = "Link that opens the created record in the ServiceNow UI."
    )
    @TicketingField(role = TicketingField.Role.TICKET_URL)
    private String url;
}
