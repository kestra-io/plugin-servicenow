package io.kestra.plugin.servicenow;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(title = "Upsert a ServiceNow configuration item through IRE", description = """
    Uses Identification and Reconciliation, never direct Table API writes. dataSource must be a
    registered discovery_source choice. The query endpoint resolves the proposed operation first;
    the instance's identification and reconciliation rules decide which CI is written.
    verify defaults true and checks requested fields through read-only CMDB API calls. Silent field
    drops and per-item errors fail even on HTTP 200. dryRun uses only the IRE query endpoint.
    owned_by supports sys_id, email, user_name or name; configure referenceTables for other references.
    Human lookup and verification require read ACLs. Optional changeSysId rechecks approval immediately
    before committing. Task timeout defaults to 1 minute and is capped at 10 minutes.
    """)
@Plugin(examples = @Example(title = "Reconcile a Linux server and verify the fields", full = true, code = """
    id: servicenow_reconcile_ci
    namespace: company.operations
    tasks:
      - id: write
        type: io.kestra.plugin.servicenow.UpsertCI
        domain: "{{ secret('SNOW_DOMAIN') }}"
        username: "{{ secret('SNOW_USERNAME') }}"
        password: "{{ secret('SNOW_PASSWORD') }}"
        className: cmdb_ci_linux_server
        dataSource: Kestra
        sourceNativeKey: inventory-web-01
        values:
          name: web-01
          serial_number: ABC123
          ip_address: 10.20.0.11
        verify: true
    """))
public class UpsertCI extends AbstractServiceNowPollingTask implements RunnableTask<UpsertCI.Output> {
    private static final Set<String> OPERATIONS = Set.of("INSERT", "UPDATE", "NO_CHANGE", "UPDATE_WITH_DOWNGRADE", "UPDATE_WITH_SWITCH", "UPDATE_WITH_UPGRADE");

    @NotNull
    @Schema(title = "CMDB class", description = "CMDB class/table API name, for example cmdb_ci_linux_server. IRE validates the class.")
    @PluginProperty(group = "main")
    private Property<String> className;
    @NotNull
    @Schema(title = "Registered data source", description = "Exact discovery_source choice used as sysparm_data_source. Required; there is no direct-write fallback.")
    @PluginProperty(group = "main")
    private Property<String> dataSource;
    @NotNull
    @Schema(title = "CI values", description = "Between 1 and 256 scalar API fields, at most 1 MiB. Reference fields are resolved to sys_ids before IRE.")
    @PluginProperty(group = "main")
    @ToString.Exclude
    private Property<Map<String, Object>> values;
    @Schema(title = "Source-native key", description = "Optional stable identifier from this source, passed in sys_object_source_info.")
    @PluginProperty(group = "source")
    private Property<String> sourceNativeKey;
    @Schema(title = "Reference tables", description = "Optional field-to-table mapping for additional CI reference fields. owned_by defaults to sys_user.")
    @PluginProperty(group = "advanced")
    private Property<Map<String, String>> referenceTables;
    @Schema(title = "Approved change sys_id", description = "Optional. Re-reads approval through the Change API immediately before a persisted IRE write.")
    @PluginProperty(group = "reliability")
    private Property<String> changeSysId;
    @Builder.Default
    @Schema(title = "Verify persisted fields", description = "True by default. Reads the CI before and after the write and fails when requested fields differ.", defaultValue = "true")
    @PluginProperty(group = "reliability")
    private Property<Boolean> verify = Property.ofValue(true);
    @Builder.Default
    @Schema(title = "Dry run", description = "False by default. Uses identifyreconcile/query without a persisted write. Proposed inserts have no committed sys_id.", defaultValue = "false")
    @PluginProperty(group = "main")
    private Property<Boolean> dryRun = Property.ofValue(false);

    @Override
    public Output run(RunContext context) throws Exception {
        try (var poll = begin(context, Duration.ofMinutes(1), Duration.ofMinutes(10))) {
            var rClass = ServiceNowValues.name(context.render(className).as(String.class).orElse(null), "className");
            var rSource = ServiceNowValues.required(context.render(dataSource).as(String.class).orElse(null), "dataSource");
            if (rSource.length() > 255) {
                throw new IllegalArgumentException("ServiceNow dataSource must be at most 255 characters. Use the registered discovery_source value.");
            }
            var rValues = ServiceNowValues.stringValues(context.render(values).asMap(String.class, Object.class));
            var rVerify = context.render(verify).as(Boolean.class).orElse(true);
            var rDryRun = context.render(dryRun).as(Boolean.class).orElse(false);
            var rChange = context.render(changeSysId).as(String.class).orElse(null);
            if (rChange != null) {
                rChange = ServiceNowValues.sysId(rChange, "changeSysId");
            }
            var api = api(context, poll);
            var references = new LinkedHashMap<String, String>(context.render(referenceTables).asMap(String.class, String.class));
            if (rValues.containsKey("owned_by")) {
                references.putIfAbsent("owned_by", "sys_user");
            }
            for (var entry : references.entrySet()) {
                ServiceNowValues.name(entry.getKey(), "reference field");
                ServiceNowValues.name(entry.getValue(), "reference table");
                if (rValues.containsKey(entry.getKey()) && !rValues.get(entry.getKey()).isEmpty()) {
                    var fields = entry.getValue().equals("sys_user") ? List.of("email", "user_name", "name") : List.of("name");
                    rValues.put(entry.getKey(), ServiceNowRecords.reference(api, entry.getValue(), rValues.get(entry.getKey()), fields));
                }
            }
            var item = new LinkedHashMap<String, Object>();
            item.put("className", rClass);
            item.put("values", rValues);
            var rKey = context.render(sourceNativeKey).as(String.class).orElse(null);
            if (rKey != null) {
                item.put("sys_object_source_info", Map.of("source_name", rSource, "source_native_key", ServiceNowValues.required(rKey, "sourceNativeKey")));
            }
            var body = Map.of("items", List.of(item));
            var params = Map.of("sysparm_data_source", rSource);
            var proposed = result(api.post("api/now/identifyreconcile/query", params, body), true, rClass);
            var proposedId = id(proposed, true);
            Map<String, Object> before = Map.of();
            if (rVerify && proposedId != null) {
                before = snapshot(ci(api, proposedId), rValues.keySet());
            }
            if (rDryRun) {
                poll.check();
                return Output.builder().sysId(proposedId).operation(proposed.path("operation").asText()).before(before)
                    .after(new LinkedHashMap<>(rValues)).verified(false).dryRun(true).build();
            }
            if (rChange != null) {
                ServiceNowRecords.requireApproved(api, rChange);
            }
            var written = result(api.post("api/now/identifyreconcile", params, body), false, rClass);
            var id = id(written, false);
            var operation = written.path("operation").asText();
            Map<String, Object> after = Map.of();
            if (rVerify) {
                var record = ci(api, id);
                ServiceNowValues.verify(record, rValues);
                after = snapshot(record, rValues.keySet());
            }
            poll.check();
            context.logger().info("ServiceNow IRE {} for CI {}", operation, id);
            return Output.builder().sysId(id).operation(operation).before(before).after(after).verified(rVerify).dryRun(false).build();
        }
    }

    private JsonNode result(JsonNode response, boolean preview, String requestedClass) {
        var result = response.path("result");
        if (!result.isObject() || !result.path("items").isArray() || result.path("items").size() != 1) {
            throw new IllegalStateException("ServiceNow IRE must return exactly one item result. Check the registered source and payload.");
        }
        for (var group : List.of("items", "relations", "additionalCommittedItems", "additionalCommittedRelations")) {
            var rows = result.path(group);
            if (!rows.isMissingNode() && !rows.isArray()) {
                throw new IllegalStateException("ServiceNow IRE returned a malformed result group. Check the API response.");
            }
            if (rows.isArray()) {
                for (var row : rows) {
                    var errors = row.path("errors");
                    var count = row.path("errorCount");
                    if (
                        (group.equals("items") || !count.isMissingNode()) && (!count.isIntegralNumber() || !count.canConvertToInt() || count.asInt() < 0)
                            || !errors.isMissingNode() && !errors.isNull() && !errors.isArray()
                    ) {
                        throw new IllegalStateException("ServiceNow IRE returned a malformed errorCount/errors value. Stop and inspect the result before retrying.");
                    }
                    if (count.asInt(0) > 0 || errors.isArray() && !errors.isEmpty()) {
                        var details = new java.util.ArrayList<String>();
                        if (errors.isArray()) {
                            errors.forEach(error -> details.add(error.path("message").asText("IRE error")));
                        }
                        throw new IllegalStateException(
                            "ServiceNow IRE %s reported item errors%s. Check identification, source authorization and required fields."
                                .formatted(preview ? "preview" : "write", details.isEmpty() ? "" : ": " + String.join("; ", details))
                        );
                    }
                }
            }
        }
        var item = result.path("items").get(0);
        if (!OPERATIONS.contains(item.path("operation").asText())) {
            throw new IllegalStateException("ServiceNow IRE returned an unknown operation. Stop and inspect the API result before retrying.");
        }
        var resultClass = ServiceNowValues.name(item.path("className").asText(null), "IRE result className");
        if (!item.path("operation").asText().startsWith("UPDATE_WITH_") && !requestedClass.equals(resultClass)) {
            throw new IllegalStateException("ServiceNow IRE returned another class than requested. Stop and inspect the API result before retrying.");
        }
        return item;
    }

    private String id(JsonNode item, boolean preview) {
        var value = item.path("sysId").asText(null);
        if (preview && "INSERT".equals(item.path("operation").asText()) && (value == null || value.isBlank() || "Unknown".equalsIgnoreCase(value))) {
            return null;
        }
        return ServiceNowValues.responseId(value, "IRE sys_id");
    }

    private JsonNode ci(ServiceNowApi api, String id) throws Exception {
        var base = ServiceNowRecords.tableRecord(api, "cmdb_ci", id);
        var actualClass = ServiceNowValues.field(base, "sys_class_name");
        return actualClass == null || "cmdb_ci".equals(actualClass) ? base : ServiceNowRecords.tableRecord(api, actualClass, id);
    }

    private Map<String, Object> snapshot(JsonNode record, Set<String> fields) {
        var result = new LinkedHashMap<String, Object>();
        for (var field : fields) {
            var node = ServiceNowValues.value(record.get(field));
            result.put(field, node == null || node.isNull() || node.isMissingNode() ? null : ServiceNowValues.text(node));
        }
        return result;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Reconciled CI sys_id", description = "Null for a proposed INSERT in dryRun.")
        private final String sysId;
        @Schema(title = "IRE operation", description = "Preserves INSERT, UPDATE, NO_CHANGE and the documented reclassification variants.")
        private final String operation;
        @Schema(title = "Requested fields before the write", description = "Only read when verify is true and the preview identifies an existing CI.")
        private final Map<String, Object> before;
        @Schema(title = "Requested fields after the write", description = "Read from the CI when verified; proposed values in dryRun.")
        private final Map<String, Object> after;
        @Schema(title = "Persisted fields verified", description = "False in dryRun or when verify is false.")
        private final Boolean verified;
        @Schema(title = "Dry run")
        private final Boolean dryRun;
    }
}
