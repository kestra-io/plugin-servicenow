package io.kestra.plugin.servicenow;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Data;
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
@Schema(title = "Load records through ServiceNow Import Sets", description = """
    Sends bounded input rows to the synchronous Import Set API. The instance's transform maps own
    coalescing, mapping and reconciliation. A successful HTTP response can still contain a failed
    transform result. ERROR fails by default; IGNORED is a valid no-change outcome.
    Each input can produce multiple transform results. Results default to an ION file.
    The load is not transactional. A failed load reports its partial-results URI; retries may replay
    accepted rows, so configure coalescing on the server. No insertMultiple acknowledgement is treated
    as completed ingestion. The internal polling budget defaults to 10 minutes, maximum 24 hours.
    """)
@Plugin(examples = @Example(title = "Load computer inventory through a transform map", full = true, code = """
    id: servicenow_import_inventory
    namespace: company.operations
    tasks:
      - id: load
        type: io.kestra.plugin.servicenow.LoadImportSet
        domain: "{{ secret('SNOW_DOMAIN') }}"
        username: "{{ secret('SNOW_USERNAME') }}"
        password: "{{ secret('SNOW_PASSWORD') }}"
        stagingTable: imp_computer
        from:
          - name: web-01
            serial_number: ABC123
            ram: 8192
        fetchType: STORE
    """))
public class LoadImportSet extends AbstractServiceNowPollingTask implements RunnableTask<LoadImportSet.Output>, Data.From {
    private static final long MAX_INPUT_BYTES = 8 * 1024 * 1024;

    @NotNull
    @Schema(title = "Staging table", description = """
        Import staging table API name. Configure its transform maps and coalescing before loading.
        """)
    @PluginProperty(group = "destination")
    private Property<String> stagingTable;
    @NotNull
    @Schema(title = Data.From.TITLE, description = Data.From.DESCRIPTION + """

        Scalar fields are sent as strings. Null and nested values fail before any write.
        Input is capped at 8 MiB with at most 1 MiB per row and 256 fields per row.
        """, anyOf = { String.class, List.class, Map.class })
    @PluginProperty(dynamic = true, group = "source")
    @ToString.Exclude
    private Object from;
    @Builder.Default
    @Schema(title = "Maximum input rows", description = """
        Between 1 and 10000, default 1000. Exceeding the limit fails before writing.
        """, defaultValue = "1000")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxRows = Property.ofValue(1000);
    @Schema(title = "Statuses that fail the load", description = """
        Defaults to [ERROR]. IGNORED is a normal no-change result. Unknown statuses always fail.
        """)
    @PluginProperty(group = "reliability")
    private Property<List<Status>> failOn;
    @Builder.Default
    @Schema(
        title = "Stop on the first failed transform", description = """
            True by default. False processes the remaining input rows before failing with the partial results.
            """, defaultValue = "true"
    )
    @PluginProperty(group = "reliability")
    private Property<Boolean> failFast = Property.ofValue(true);
    @Builder.Default
    @Schema(title = "Fetch type", description = """
        STORE returns an ION URI, FETCH returns rows, FETCH_ONE returns the first result and NONE returns counts only.
        """, defaultValue = "STORE")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);
    @Schema(title = "Approved change sys_id", description = """
        Optional. Rechecks approval through the Change API immediately before each input-row write.
        """)
    @PluginProperty(group = "reliability")
    private Property<String> changeSysId;

    public enum Status {
        INSERTED,
        UPDATED,
        IGNORED,
        SKIPPED,
        ERROR
    }

    @Override
    public Output run(RunContext context) throws Exception {
        try (var poll = begin(context, Duration.ofMinutes(10), Duration.ofHours(24))) {
            var rTable = ServiceNowValues.name(context.render(stagingTable).as(String.class).orElse(null), "stagingTable");
            var rLimit = context.render(maxRows).as(Integer.class).orElse(1000);
            var rFetch = context.render(fetchType).as(FetchType.class).orElse(FetchType.STORE);
            Set<Status> rFailOn = failOn == null ? Set.of(Status.ERROR) : new HashSet<>(context.render(failOn).asList(Status.class));
            var rFailFast = context.render(failFast).as(Boolean.class).orElse(true);
            var rChange = context.render(changeSysId).as(String.class).orElse(null);
            if (rChange != null) {
                rChange = ServiceNowValues.sysId(rChange, "changeSysId");
            }
            var rRows = read(context, rLimit, poll);
            var api = api(context, poll);
            try (var results = new Results(context, rFetch)) {
                var failed = false;
                String failureDetail = null;
                var submitted = 0;
                try {
                    for (var index = 0; index < rRows.size(); index++) {
                        poll.check();
                        if (rChange != null) {
                            ServiceNowRecords.requireApproved(api, rChange);
                        }
                        var response = api.post("api/now/import/%s".formatted(rTable), Map.of(), rRows.get(index));
                        submitted++;
                        var rows = response.path("result");
                        if (!rows.isArray() || rows.isEmpty()) {
                            throw new IllegalStateException("ServiceNow returned no transform results. Configure a transform map for the staging table.");
                        }
                        for (var row : rows) {
                            var result = result(row, index, response);
                            results.add(result);
                            if (rFailOn.contains(result.getStatus())) {
                                failed = true;
                                if (failureDetail == null) {
                                    var message = result.getMessage();
                                    failureDetail = "Input row %d returned %s%s"
                                        .formatted(index, result.getStatus(), message.isBlank() ? "" : ": " + message.substring(0, Math.min(1000, message.length())));
                                }
                            }
                        }
                        if (failed && rFailFast) {
                            break;
                        }
                    }
                    if (failed) {
                        throw new IllegalStateException("%s. Check the transform messages and coalescing rules.".formatted(failureDetail));
                    }
                    poll.check();
                    var uri = rFetch == FetchType.STORE ? results.publish() : null;
                    poll.check();
                    context.logger().info("ServiceNow imported {} input rows with {} transform results", submitted, results.size);
                    return Output.builder().submittedRows(submitted).counts(results.counts).rows(results.rows).row(results.first)
                        .uri(uri).build();
                } catch (Exception e) {
                    var uri = results.publish();
                    if (e instanceof CancellationException) {
                        throw new CancellationException("ServiceNow import cancelled. Partial results: %s. Check accepted rows before retrying.".formatted(uri));
                    }
                    throw new IllegalStateException(
                        "ServiceNow import stopped after %d decoded input responses. Partial results: %s. Check the last request before retrying; it may already have been accepted. %s"
                            .formatted(submitted, uri, e.getMessage()),
                        e
                    );
                }
            }
        }
    }

    private List<Map<String, String>> read(RunContext context, int maximum, Poll poll) throws Exception {
        if (from == null || from instanceof String text && text.length() > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("ServiceNow from is required and must not exceed 8 MiB.");
        }
        var bytes = new AtomicLong();
        var wait = poll.remaining().compareTo(Duration.ofSeconds(30)) < 0 ? poll.remaining() : Duration.ofSeconds(30);
        List<Map<String, String>> rows;
        try {
            rows = Data.from(from).read(context).take(maximum + 1L).map(row ->
            {
                var values = ServiceNowValues.stringValues(row);
                var size = values.entrySet().stream().mapToLong(
                    entry -> entry.getKey().getBytes(StandardCharsets.UTF_8).length
                        + entry.getValue().getBytes(StandardCharsets.UTF_8).length
                ).sum();
                if (bytes.addAndGet(size) > MAX_INPUT_BYTES) {
                    throw new IllegalArgumentException("ServiceNow input exceeds 8 MiB. Split the load.");
                }
                return values;
            }).collectList().block(wait);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read ServiceNow import data. Check the Data input, field values and size limits.", e);
        }
        if (rows == null || rows.isEmpty() || rows.size() > maximum) {
            throw new IllegalArgumentException("ServiceNow import needs between 1 and maxRows input records.");
        }
        poll.check();
        return rows;
    }

    private Result result(JsonNode row, int index, JsonNode response) {
        if (!row.isObject()) {
            throw new IllegalStateException("ServiceNow transform result must be an object. Check the Import Set API response.");
        }
        Status status;
        try {
            status = Status.valueOf(ServiceNowValues.required(ServiceNowValues.field(row, "status"), "transform status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ServiceNow returned an unknown or missing transform status. Check the transform map response.", e);
        }
        var id = ServiceNowValues.field(row, "sys_id");
        if (status == Status.INSERTED || status == Status.UPDATED) {
            id = ServiceNowValues.sysId(id, "transform target sys_id");
        }
        var messages = List.of(row.path("status_message").asText(""), row.path("error_message").asText(""), row.path("message").asText(""))
            .stream().filter(message -> !message.isBlank()).toList();
        return Result.builder().inputIndex(index).importSet(response.path("import_set").asText(null)).status(status)
            .table(ServiceNowValues.field(row, "table")).sysId(id).transformMap(ServiceNowValues.field(row, "transform_map"))
            .message(String.join("; ", messages)).build();
    }

    private static final class Results implements AutoCloseable {
        private final RunContext context;
        private final FetchType fetch;
        private final Path file;
        private final OutputStream writer;
        private final Map<Status, Integer> counts = new EnumMap<>(Status.class);
        private final List<Result> rows;
        private Result first;
        private int size;
        private URI uri;

        private Results(RunContext context, FetchType fetch) throws IOException {
            this.context = context;
            this.fetch = fetch;
            file = context.workingDir().createTempFile(".ion");
            writer = new BufferedOutputStream(Files.newOutputStream(file));
            rows = fetch == FetchType.FETCH ? new ArrayList<>() : null;
            for (var status : Status.values()) {
                counts.put(status, 0);
            }
        }

        private void add(Result result) throws IOException {
            if (++size > 10000) {
                throw new IllegalStateException("ServiceNow import exceeds 10000 transform results. Split the load or reduce transform maps.");
            }
            FileSerde.write(writer, result);
            counts.merge(result.getStatus(), 1, Integer::sum);
            if (fetch == FetchType.FETCH) {
                rows.add(result);
            } else if (fetch == FetchType.FETCH_ONE && first == null) {
                first = result;
            }
        }

        private URI publish() throws IOException {
            if (uri == null) {
                writer.flush();
                uri = context.storage().putFile(file.toFile());
            }
            return uri;
        }

        @Override
        public void close() throws IOException {
            try {
                writer.close();
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    @Builder
    @Getter
    public static class Result {
        @Schema(title = "Zero-based input row index")
        private final Integer inputIndex;
        @Schema(title = "Import set name")
        private final String importSet;
        @Schema(title = "Transform status")
        private final Status status;
        @Schema(title = "Target table")
        private final String table;
        @Schema(title = "Target sys_id")
        private final String sysId;
        @Schema(title = "Transform map name")
        private final String transformMap;
        @Schema(title = "Transform messages")
        private final String message;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Input rows with accepted HTTP responses", description = """
            An accepted response is not necessarily a successful transformation. Inspect counts and per-result statuses.
            """)
        private final Integer submittedRows;
        @Schema(title = "Per-transform status counts", description = """
            A row can run multiple transform maps. Counts refer to transform results, not input rows.
            """)
        private final Map<Status, Integer> counts;
        @Schema(title = "Transform results", description = """
            Present with FETCH.
            """)
        private final List<Result> rows;
        @Schema(title = "First transform result", description = """
            Present with FETCH_ONE.
            """)
        private final Result row;
        @Schema(title = "ION results URI", description = """
            Present with STORE. A failed load includes its partial-results URI in the error.
            """)
        private final URI uri;
    }
}
