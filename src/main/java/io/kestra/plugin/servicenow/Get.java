package io.kestra.plugin.servicenow;

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
import org.slf4j.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Retrieve multiple records from a ServiceNow table."
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
        )
    }
)
public class Get extends AbstractServiceNow implements RunnableTask<Get.Output> {
    @NotNull
    @Schema(
        title = "ServiceNow table."
    )
    private Property<String> table;

    @Override
    public Get.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(URI.create(baseUri(runContext) + "api/now/table/" + runContext.render(this.table).as(String.class).orElseThrow()))
            .method("GET");

        HttpResponse<GetResult> response = this.request(runContext, requestBuilder, GetResult.class);

        if (response.getBody() == null) {
            throw new IllegalStateException("Empty body on '" + response + "'");
        }

        logger.info("Post done with result '{}'", response.getBody());

        List<Map<String, Object>> results = response.getBody().getResult();

        return Output.builder()
            .results(results)
            .size(results.size())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The result data."
        )
        private List<Map<String, Object>> results;

        @Schema(
            title = "Results size."
        )
        private Integer size;
    }

    @Data
    @NoArgsConstructor
    public static class GetResult {
        List<Map<String, Object>> result;
    }
}
