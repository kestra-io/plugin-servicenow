package io.kestra.plugin.servicenow;

import java.util.LinkedHashMap;
import java.util.Map;

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
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a ServiceNow incident",
    description = "Creates a record in the `incident` table from typed fields and returns its number, `sys_id` and UI link. Use `Post` to write to any other table."
)
@Plugin(
    examples = {
        @Example(
            title = "Create an incident.",
            full = true,
            code = """
                id: servicenow_create_incident
                namespace: company.team

                tasks:
                  - id: create_incident
                    type: io.kestra.plugin.servicenow.CreateIncident
                    domain: "snow_domain"
                    username: "snow_username"
                    password: "{{ secret('SNOW_PASSWORD') }}"
                    shortDescription: "Payment API is failing"
                    incidentDescription: "Checkout requests return a 502 since 10:00 UTC."
                    urgency: "1"
                """
        )
    }
)
public class CreateIncident extends AbstractServiceNow implements RunnableTask<CreatedRecord>, TicketingTaskInterface {
    private static final String TABLE = "incident";

    @NotNull
    @Schema(title = "Short description", description = "One-line summary of the incident.")
    @PluginProperty(group = "main")
    @TicketingField(role = TicketingField.Role.CASE_TITLE)
    private Property<String> shortDescription;

    @Schema(title = "Description", description = "Detailed description of the incident.")
    @PluginProperty(group = "main")
    @TicketingField(role = TicketingField.Role.CASE_DESCRIPTION)
    private Property<String> incidentDescription;

    @Schema(title = "Urgency", description = "`1` (high), `2` (medium) or `3` (low). Leave blank to use the ServiceNow default.")
    @PluginProperty(group = "advanced")
    @TicketingField(role = TicketingField.Role.CASE_SEVERITY, valueMap = {"CRITICAL=1", "HIGH=1", "MEDIUM=2", "LOW=3"})
    private Property<String> urgency;

    @Schema(title = "Impact", description = "`1` (high), `2` (medium) or `3` (low); with the urgency it sets the incident priority. Leave blank to use the ServiceNow default.")
    @PluginProperty(group = "advanced")
    @TicketingField(role = TicketingField.Role.CASE_SEVERITY, valueMap = {"CRITICAL=1", "HIGH=2", "MEDIUM=2", "LOW=3"})
    private Property<String> impact;

    @Schema(title = "Additional fields", description = "Any other incident fields, merged into the request body; the typed fields above take precedence.")
    @PluginProperty(group = "advanced")
    private Property<Map<String, Object>> data;

    @Override
    public CreatedRecord run(RunContext runContext) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(runContext.render(data).asMap(String.class, Object.class));
        body.put(
            "short_description",
            runContext.render(shortDescription).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("ServiceNow 'shortDescription' is required to create an incident."))
        );
        runContext.render(incidentDescription).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> body.put("description", value));
        runContext.render(urgency).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> body.put("urgency", value));
        runContext.render(impact).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> body.put("impact", value));

        return createRecord(runContext, TABLE, body);
    }
}
