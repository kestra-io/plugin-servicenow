package io.kestra.plugin.servicenow;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Update a record in a ServiceNow table.")
public class Update extends AbstractServiceNow implements RunnableTask<Update.Output> {

    @NotNull
    @Schema(title = "ServiceNow table.")
    private Property<String> table;

    @NotNull
    @Schema(title = "The sys_id of the record to update.")
    private Property<String> sysId;

    @NotNull
    @Schema(title = "The fields to update.")
    private Property<Map<String, Object>> data;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String table = runContext.render(this.table).as(String.class).orElseThrow();
        String sysId = runContext.render(this.sysId).as(String.class).orElseThrow();

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(URI.create(baseUri(runContext) + "api/now/table/" + table + "/" + sysId))
            .method("PUT")
            .body(HttpRequest.JsonRequestBody.builder()
                .content(runContext.render(data).asMap(String.class, Object.class))
                .build());

        HttpResponse<UpdateResult> response = this.request(runContext, requestBuilder, UpdateResult.class);

        return Output.builder()
            .result(response.getBody().getResult())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        private Map<String, Object> result;
    }

    @Data
    @NoArgsConstructor
    public static class UpdateResult {
        Map<String, Object> result;
    }
}
