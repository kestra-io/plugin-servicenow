package io.kestra.plugin.servicenow;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

final class ServiceNowRecords {
    private ServiceNowRecords() {
    }

    static JsonNode change(ServiceNowApi api, String id) throws Exception {
        return matchingRecord(api.get("api/sn_chg_rest/change/%s".formatted(id), Map.of()), id);
    }

    static JsonNode tableRecord(ServiceNowApi api, String table, String id) throws Exception {
        return matchingRecord(api.get("api/now/table/%s/%s".formatted(ServiceNowValues.name(table, "table"), id), Map.of("sysparm_display_value", false)), id);
    }

    private static JsonNode matchingRecord(JsonNode response, String id) {
        var record = ServiceNowValues.record(response);
        if (!id.equals(ServiceNowValues.recordId(record))) {
            throw new IllegalStateException("ServiceNow returned a different sys_id than requested. Stop and check the instance/API response.");
        }
        return record;
    }

    static String reference(ServiceNowApi api, String table, String identifier, List<String> fields) throws Exception {
        if (ServiceNowValues.isSysId(identifier)) {
            return ServiceNowValues.sysId(identifier, "reference");
        }
        var value = ServiceNowValues.required(identifier, "reference identifier");
        if (value.length() > 255 || value.contains("^") || value.contains("\r") || value.contains("\n") || value.toLowerCase(Locale.ROOT).contains("javascript:")) {
            throw new IllegalArgumentException(
                "ServiceNow reference identifiers must be at most 255 characters and must not contain encoded-query operators or scripts. Use a sys_id for unusual names."
            );
        }
        for (var field : fields) {
            var query = "%s=%s".formatted(ServiceNowValues.name(field, "reference lookup field"), value);
            var response = api
                .get("api/now/table/%s".formatted(ServiceNowValues.name(table, "reference table")), Map.of("sysparm_query", query, "sysparm_limit", 2, "sysparm_fields", "sys_id," + field));
            var result = response.path("result");
            if (!result.isArray()) {
                throw new IllegalStateException("ServiceNow reference lookup must return a result array. Check reference-table read ACLs.");
            }
            if (result.size() > 1) {
                throw new IllegalStateException("ServiceNow reference matches multiple %s records. Supply an unambiguous sys_id.".formatted(table));
            }
            if (result.size() == 1) {
                var record = result.get(0);
                var returned = ServiceNowValues.field(record, field);
                if (returned == null || !value.equalsIgnoreCase(returned.trim())) {
                    throw new IllegalStateException("ServiceNow reference lookup did not match the requested identifier. Check the filter and reference-table ACLs.");
                }
                return ServiceNowValues.recordId(record);
            }
        }
        throw new IllegalArgumentException("ServiceNow reference was not found in %s. Check the identifier and read ACLs or supply a sys_id.".formatted(table));
    }

    static List<ApprovalRecord> approvals(ServiceNowApi api, String changeId, int maximum) throws Exception {
        var rows = new ArrayList<ApprovalRecord>();
        for (var offset = 0;; offset += 100) {
            var response = api.get(
                "api/now/table/sysapproval_approver", Map.of(
                    "sysparm_query", "sysapproval=%s^ORDERBYsys_id".formatted(changeId), "sysparm_limit", 100, "sysparm_offset", offset,
                    "sysparm_fields", "sys_id,sysapproval,approver.name,approver.email,state,comments,sys_updated_on"
                )
            );
            var result = response.path("result");
            if (!result.isArray()) {
                throw new IllegalStateException("ServiceNow approval audit must return a result array. Grant read access to sysapproval_approver and approver fields.");
            }
            for (var row : result) {
                if (!changeId.equals(ServiceNowValues.sysId(ServiceNowValues.field(row, "sysapproval"), "approval change reference"))) {
                    throw new IllegalStateException("ServiceNow returned an approval for another change. Stop and check the approval filter/ACLs.");
                }
                var state = ServiceNowValues.required(ServiceNowValues.field(row, "state"), "approval state");
                var updated = ServiceNowValues.field(row, "sys_updated_on");
                var instant = updated == null || updated.isBlank() ? null
                    : LocalDateTime.parse(ServiceNowValues.date(updated, "approval sys_updated_on"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC);
                rows.add(
                    ApprovalRecord.builder().sysId(ServiceNowValues.recordId(row))
                        .name(ServiceNowValues.field(row, "approver.name")).email(ServiceNowValues.field(row, "approver.email"))
                        .state(state).comments(ServiceNowValues.field(row, "comments")).updatedAt(instant).build()
                );
                if (rows.size() > maximum) {
                    throw new IllegalStateException("ServiceNow approval audit exceeds maxApprovers. Narrow the change or raise the limit.");
                }
            }
            if (result.size() < 100) {
                return rows;
            }
        }
    }

    static String normaliseState(String value) {
        if (value == null) {
            return "";
        }
        var text = value.trim();
        if (text.matches("[+-]?[0-9]+(?:\\.0+)?")) {
            return new java.math.BigDecimal(text).stripTrailingZeros().toPlainString();
        }
        return text.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static boolean isState(JsonNode record, String wanted) {
        return normaliseState(wanted).equals(normaliseState(ServiceNowValues.field(record, "state")))
            || normaliseState(wanted).equals(normaliseState(record.path("state").path("display_value").asText()));
    }

    static String stateId(JsonNode next, String wanted) {
        var states = next.path("available_states");
        var labels = next.path("state_label");
        if (!states.isArray() || !labels.isObject()) {
            throw new IllegalStateException("ServiceNow nextstates must contain available_states and state_label. Check the enabled Change Management API.");
        }
        var matches = new ArrayList<String>();
        for (var state : states) {
            var id = ServiceNowValues.text(state);
            if (normaliseState(wanted).equals(normaliseState(id)) || normaliseState(wanted).equals(normaliseState(labels.path(id).asText()))) {
                if (!matches.contains(id)) {
                    matches.add(id);
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                "ServiceNow target state %s is unavailable or ambiguous. Check nextstates and provide explicit via/state values for the instance's model.".formatted(wanted)
            );
        }
        return matches.getFirst();
    }

    static void allowed(JsonNode next, String from, String to) {
        var transitions = next.get("state_transitions");
        if (transitions == null || transitions.isNull() || transitions.isArray() && transitions.isEmpty()) {
            return;
        }
        var matches = new ArrayList<JsonNode>();
        flattenTransitions(transitions, matches, 0);
        var applicable = matches.stream().filter(edge -> from.equals(ServiceNowValues.field(edge, "from_state")) && to.equals(ServiceNowValues.field(edge, "to_state"))).toList();
        if (applicable.stream().anyMatch(edge -> edge.path("transition_available").isBoolean() && edge.path("transition_available").asBoolean() && conditionsPassed(edge))) {
            return;
        }
        var conditions = applicable.stream().flatMap(edge ->
        {
            var names = new ArrayList<String>();
            var values = edge.path("conditions");
            if (values.isArray()) {
                for (var condition : values) {
                    if (!condition.path("passed").asBoolean()) {
                        names.add(condition.path("condition").path("name").asText("Unmet model condition"));
                    }
                }
            }
            return names.stream();
        }).distinct().toList();
        throw new IllegalStateException(
            "ServiceNow model refuses transition %s to %s%s. Satisfy the model conditions before retrying."
                .formatted(from, to, conditions.isEmpty() ? "" : ": " + String.join("; ", conditions))
        );
    }

    private static boolean conditionsPassed(JsonNode transition) {
        var conditions = transition.path("conditions");
        if (!conditions.isArray()) {
            return false;
        }
        for (var condition : conditions) {
            if (!condition.path("passed").isBoolean() || !condition.path("passed").asBoolean()) {
                return false;
            }
        }
        return true;
    }

    record ChangeModel(String name, String sysId) {
    }

    static ChangeModel changeModel(ServiceNowApi api, String identifier) throws Exception {
        String expectedId = null;
        if (ServiceNowValues.isSysId(identifier)) {
            expectedId = ServiceNowValues.sysId(identifier, "changeModel");
            var record = matchingRecord(api.get("api/sn_chg_rest/change/model/%s".formatted(expectedId), Map.of()), expectedId);
            identifier = ServiceNowValues.field(record, "name");
        }
        var name = ServiceNowValues.required(identifier, "changeModel");
        if (name.length() > 200 || name.contains("^") || name.contains("\r") || name.contains("\n") || name.toLowerCase(Locale.ROOT).contains("javascript:")) {
            throw new IllegalArgumentException("ServiceNow changeModel must be an unambiguous model name or sys_id without query operators.");
        }
        var rows = api.get("api/sn_chg_rest/change/model", Map.of("sysparm_query", "name=%s".formatted(name), "sysparm_limit", 2)).path("result");
        if (!rows.isArray() || rows.size() != 1 || !name.equalsIgnoreCase(ServiceNowValues.field(rows.get(0), "name"))) {
            throw new IllegalArgumentException("ServiceNow change model is unavailable or ambiguous. Check the name and API read ACLs or supply a sys_id.");
        }
        var id = ServiceNowValues.recordId(rows.get(0));
        if (expectedId != null && !expectedId.equals(id)) {
            throw new IllegalStateException("ServiceNow model name resolved to another sys_id. Check the model names before creating a change.");
        }
        return new ChangeModel(ServiceNowValues.field(rows.get(0), "name"), id);
    }

    static void requireApproved(ServiceNowApi api, String changeId) throws Exception {
        var record = change(api, changeId);
        if (
            !"approved".equals(normaliseState(ServiceNowValues.field(record, "approval"))) || isState(record, "Closed") || isCancelled(record)
                || "false".equalsIgnoreCase(ServiceNowValues.field(record, "active"))
        ) {
            throw new IllegalStateException("ServiceNow change approval is no longer approved. Stop and request approval again before writing.");
        }
    }

    static boolean isCancelled(JsonNode record) {
        return isState(record, "Canceled") || isState(record, "Cancelled");
    }

    private static void flattenTransitions(JsonNode node, List<JsonNode> output, int depth) {
        if (depth > 4 || output.size() > 1000) {
            throw new IllegalStateException("ServiceNow nextstates exceeds transition limits. Check the response/model.");
        }
        if (node.isArray()) {
            node.forEach(child -> flattenTransitions(child, output, depth + 1));
        } else if (node.isObject() && node.has("from_state") && node.has("to_state")) {
            output.add(node);
        } else {
            throw new IllegalStateException("ServiceNow nextstates contains a malformed transition. Check the response/model.");
        }
    }
}
