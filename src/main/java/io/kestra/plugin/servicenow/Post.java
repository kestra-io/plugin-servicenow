package io.kestra.plugin.servicenow;

import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a record in a ServiceNow table",
    description = "Sends a JSON body to the table REST API using Basic Auth or the OAuth password grant and returns the created record."
)
@Plugin(
    examples = {
        @Example(
            title = "Create an incident using BasicAuth.",
            full = true,
            code = """
                id: servicenow_post
                namespace: company.team

                tasks:
                  - id: post
                    type: io.kestra.plugin.servicenow.Post
                    domain: "snow_domain"
                    username: "snow_username"
                    password: "{{ secret('SNOW_PASSWORD') }}"
                    table: incident
                    data:
                      short_description: "API Create Incident..."
                      requester_id: f8266e2adb16fb00fa638a3a489619d2
                      requester_for_id: a7ec77cbdefac300d322d182689619dc
                      product_id: 01a2e3c1db15f340d329d18c689ed922
                """
        ),
        @Example(
            title = "Create an incident using OAuth.",
            full = true,
            code = """
                id: servicenow_post
                namespace: company.team

                tasks:
                  - id: post
                    type: io.kestra.plugin.servicenow.Post
                    domain: "snow_domain"
                    username: "snow_username"
                    password: "{{ secret('SNOW_PASSWORD') }}"
                    clientId: "{{ secret('SNOW_CLIENT_ID') }}"
                    clientSecret: "{{ secret('SNOW_CLIENT_SECRET') }}"
                    table: incident
                    data:
                      short_description: "API Create Incident..."
                      requester_id: f8266e2adb16fb00fa638a3a489619d2
                      requester_for_id: a7ec77cbdefac300d322d182689619dc
                      product_id: 01a2e3c1db15f340d329d18c689ed922
                """
        )
    }
)
public class Post extends AbstractServiceNow implements RunnableTask<CreatedRecord> {
    @NotNull
    @Schema(
        title = "ServiceNow table",
        description = "API name of the table to insert into (for example `incident`)."
    )
    @PluginProperty(group = "destination")
    private Property<String> table;

    @NotNull
    @Schema(
        title = "Record payload",
        description = "Map rendered to JSON and sent as the request body."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> data;

    @Override
    public CreatedRecord run(RunContext runContext) throws Exception {
        return createRecord(
            runContext,
            runContext.render(this.table).as(String.class).orElseThrow(),
            runContext.render(data).asMap(String.class, Object.class)
        );
    }
}
