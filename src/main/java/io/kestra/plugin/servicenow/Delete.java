package io.kestra.plugin.servicenow;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.micronaut.http.HttpStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Delete a ServiceNow record by sys_id", description = "Calls the table REST API with DELETE using Basic Auth or the OAuth password grant and flags success when ServiceNow returns HTTP 204.")
public class Delete extends AbstractServiceNow implements RunnableTask<Delete.Output> {

    @NotNull
    @Schema(title = "ServiceNow table", description = "API name of the table containing the record.")
    private Property<String> table;

    @NotNull
    @Schema(title = "Record sys_id", description = "Unique identifier of the record to delete.")
    private Property<String> sysId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String table = runContext.render(this.table).as(String.class).orElseThrow();
        String sysId = runContext.render(this.sysId).as(String.class).orElseThrow();

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(URI.create(baseUri(runContext) + "api/now/table/" + table + "/" + sysId))
            .method("DELETE");

        HttpResponse<Void> response = this.request(runContext, requestBuilder, Void.class);

        return Output.builder()
            .deleted(HttpStatus.NO_CONTENT.getCode() == response.getStatus().getCode())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        private boolean deleted;
    }
}
