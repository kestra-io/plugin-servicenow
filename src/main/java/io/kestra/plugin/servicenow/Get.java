package io.kestra.plugin.servicenow;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
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
    title = "Fetch records from a ServiceNow table",
    description = "Reads rows from the specified table using Basic Auth or the OAuth password grant and returns the raw ServiceNow payload plus the count."
)
@Plugin(
    examples = {
        @Example(
            title = "Get incidents using BasicAuth.",
            full = true,
            code = """
                id: servicenow_get
                namespace: company.team

                tasks:
                  - id: get
                    type: io.kestra.plugin.servicenow.Get
                    domain: "snow_domain"
                    username: "snow_username"
                    password: "snow_password"
                    table: incident
                """
        ),
        @Example(
            title = "Get incidents using OAuth.",
            full = true,
            code = """
                id: servicenow_get
                namespace: company.team

                tasks:
                  - id: get
                    type: io.kestra.plugin.servicenow.Get
                    domain: "snow_domain"
                    username: "snow_username"
                    password: "snow_password"
                    clientId: "my_registered_kestra_application_client_id"
                    clientSecret: "my_registered_kestra_application_client_secret"
                    table: incident
                """
        ),
        @Example(
            title = "Get high-priority active incidents with pagination.",
            full = true,
            code = """
                id: servicenow_get_filtered
                namespace: company.team

                tasks:
                  - id: get
                    type: io.kestra.plugin.servicenow.Get
                    domain: "{{ secret('SNOW_DOMAIN') }}"
                    username: "{{ secret('SNOW_USERNAME') }}"
                    password: "{{ secret('SNOW_PASSWORD') }}"
                    table: incident
                    query: "active=true^priority=1"
                    fields:
                      - number
                      - short_description
                      - priority
                      - state
                    limit: 100
                    offset: 0
                """
        )
    }
)
public class Get extends AbstractServiceNow implements RunnableTask<Get.Output> {
    @NotNull
    @Schema(
        title = "ServiceNow table",
        description = "API name of the table to query (for example `incident`)."
    )
    private Property<String> table;

    @Schema(
        title = "Encoded query filter",
        description = "ServiceNow encoded query string appended as `sysparm_query` (for example `active=true^priority=1`)."
    )
    private Property<String> query;

    @Schema(
        title = "Maximum records to return",
        description = "Appended as `sysparm_limit`. When absent, ServiceNow applies its own default limit."
    )
    private Property<Integer> limit;

    @Schema(
        title = "Starting record index",
        description = "Appended as `sysparm_offset`. Use together with `limit` for page-by-page retrieval."
    )
    private Property<Integer> offset;

    @Schema(
        title = "Fields to return",
        description = "Comma-joined list of field names sent as `sysparm_fields`. When absent, all fields are returned."
    )
    private Property<List<String>> fields;

    @Override
    public Get.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        var rTable = runContext.render(this.table).as(String.class).orElseThrow();
        var baseUrl = baseUri(runContext) + "api/now/table/" + rTable;
        var queryString = buildQueryString(runContext);
        var fullUrl = queryString.isEmpty() ? baseUrl : baseUrl + "?" + queryString;

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(fullUrl))
            .method("GET");

        var response = this.request(runContext, requestBuilder, GetResult.class);

        if (response.getBody() == null) {
            throw new IllegalStateException("Empty body on '" + response + "'");
        }

        logger.info("Get done with result '{}'", response.getBody());

        var results = response.getBody().getResult();
        var rOffset = runContext.render(this.offset).as(Integer.class).orElse(null);

        return Output.builder()
            .results(results)
            .size(results.size())
            .offset(rOffset)
            .build();
    }

    private String buildQueryString(RunContext runContext) throws Exception {
        var parts = new ArrayList<String>();

        Optional<String> rQuery = runContext.render(this.query).as(String.class);
        if (rQuery.isPresent()) {
            parts.add("sysparm_query=" + URLEncoder.encode(rQuery.get(), StandardCharsets.UTF_8));
        }

        Optional<Integer> rLimit = runContext.render(this.limit).as(Integer.class);
        if (rLimit.isPresent()) {
            parts.add("sysparm_limit=" + rLimit.get());
        }

        Optional<Integer> rOffset = runContext.render(this.offset).as(Integer.class);
        if (rOffset.isPresent()) {
            parts.add("sysparm_offset=" + rOffset.get());
        }

        // asList returns T (List<String>) directly; it returns null when the property is absent
        List<String> rFields = runContext.render(this.fields).asList(String.class);
        if (rFields != null && !rFields.isEmpty()) {
            var joined = String.join(",", rFields);
            parts.add("sysparm_fields=" + URLEncoder.encode(joined, StandardCharsets.UTF_8));
        }

        return String.join("&", parts);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Result records",
            description = "List of rows exactly as returned by ServiceNow."
        )
        private List<Map<String, Object>> results;

        @Schema(
            title = "Result size",
            description = "Number of records returned in `results`."
        )
        private Integer size;

        @Schema(
            title = "Offset used in the request",
            description = "Value of `sysparm_offset` sent with this request. Null when no offset was specified. Add `size` to this value to get the offset for the next page."
        )
        private Integer offset;
    }

    @Data
    @NoArgsConstructor
    public static class GetResult {
        List<Map<String, Object>> result;
    }
}
