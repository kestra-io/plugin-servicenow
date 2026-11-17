package io.kestra.plugin.servicenow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;

import jakarta.inject.Inject;

@KestraTest
abstract class NativeTaskTestSupport {
    static final String CHANGE = "a".repeat(32);
    static final String APPROVER = "b".repeat(32);
    static final String USER = "c".repeat(32);
    static final String CI = "d".repeat(32);
    static final String TEMPLATE = "e".repeat(32);
    static final String GROUP = "1".repeat(32);
    static final String MODEL = "f".repeat(32);
    static final String USERNAME = "native-user";
    static final String PASSWORD = "native-password";

    @Inject
    protected RunContextFactory runContextFactory;

    protected <B extends AbstractServiceNow.AbstractServiceNowBuilder<?, ?>> B connected(B builder, WireMockRuntimeInfo server, Class<?> type) {
        builder.id(IdUtils.create());
        builder.type(type.getName());
        builder.uri(Property.ofValue(server.getHttpBaseUrl()));
        builder.username(Property.ofValue(USERNAME));
        builder.password(Property.ofValue(PASSWORD));
        return builder;
    }

    protected RunContext context(Task task) {
        return runContextFactory.of(task, Map.of());
    }

    static String json(Object value) throws Exception {
        return JacksonMapper.ofJson().writeValueAsString(value);
    }

    static String change(String state, String label, String approval, Map<String, Object> fields) throws Exception {
        var record = new LinkedHashMap<String, Object>();
        var values = new LinkedHashMap<String, Object>(Map.of("sys_id", CHANGE, "number", "CHG001", "type", "normal", "approval", approval));
        values.putAll(fields);
        values.forEach((key, value) -> record.put(key, Map.of("value", value, "display_value", value.toString())));
        record.put("state", Map.of("value", state, "display_value", label));
        return json(Map.of("result", record));
    }

    static String next(String from, String fromLabel, String to, String toLabel, boolean allowed) throws Exception {
        var labels = new LinkedHashMap<String, String>();
        labels.put("4", "Canceled");
        labels.put(from, fromLabel);
        labels.put(to, toLabel);
        return json(
            Map.of(
                "result", Map.of(
                    "available_states", List.of(from, to, "4"), "state_label", labels,
                    "state_transitions", List.of(
                        List.of(
                            Map.of(
                                "from_state", from, "to_state", to, "transition_available", allowed, "automatic_transition", false,
                                "conditions", List.of(Map.of("passed", allowed, "condition", Map.of("name", "Assignment group is not empty")))
                            )
                        )
                    )
                )
            )
        );
    }

    static String audit(String state) throws Exception {
        return json(
            Map.of(
                "result", List.of(
                    Map.of(
                        "sys_id", APPROVER, "sysapproval", CHANGE,
                        "approver.name", "Avery Example", "approver.email", "avery@example.org", "state", state,
                        "comments", "Reviewed the implementation plan", "sys_updated_on", "2026-11-17 09:00:00"
                    )
                )
            )
        );
    }

    static String imported(String status, String message) throws Exception {
        return json(
            Map.of(
                "import_set", "ISET001", "staging_table", "imp_computer", "result", List.of(
                    Map.of(
                        "status", status, "sys_id", CI, "table", "cmdb_ci_computer", "transform_map", "Computer", "error_message", message
                    )
                )
            )
        );
    }

    static String ire(String operation, String id) throws Exception {
        return json(
            Map.of(
                "result", Map.of(
                    "items", List.of(Map.of("className", "cmdb_ci_linux_server", "operation", operation, "sysId", id, "errorCount", 0, "inputIndices", List.of(0))),
                    "relations", List.of(), "additionalCommittedItems", List.of(), "additionalCommittedRelations", List.of()
                )
            )
        );
    }
}
