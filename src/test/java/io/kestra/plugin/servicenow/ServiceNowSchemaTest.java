package io.kestra.plugin.servicenow;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.docs.JsonSchemaGenerator;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.tasks.Task;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@KestraTest
class ServiceNowSchemaTest {

    @Inject
    JsonSchemaGenerator jsonSchemaGenerator;

    @Test
    @SuppressWarnings("unchecked")
    void connectionAndDestinationPropertiesAreGrouped() {
        for (Class<? extends Task> taskClass : List.of(Post.class, Get.class, Update.class, Delete.class)) {
            Map<String, Object> generate = jsonSchemaGenerator.properties(Task.class, taskClass);
            var properties = (Map<String, Map<String, Object>>) generate.get("properties");

            assertThat(group(properties.get("domain"))).isEqualTo("connection");
            assertThat(group(properties.get("username"))).isEqualTo("connection");
            assertThat(group(properties.get("password"))).isEqualTo("connection");
            assertThat(group(properties.get("clientId"))).isEqualTo("connection");
            assertThat(group(properties.get("clientSecret"))).isEqualTo("connection");

            assertThat(group(properties.get("table"))).isEqualTo("destination");

            assertThat(secret(properties.get("username"))).isEqualTo(true);
            assertThat(secret(properties.get("password"))).isEqualTo(true);
            assertThat(secret(properties.get("clientSecret"))).isEqualTo(true);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSpecificPropertiesAreGrouped() {
        Map<String, Object> generate = jsonSchemaGenerator.properties(Task.class, Get.class);
        var properties = (Map<String, Map<String, Object>>) generate.get("properties");

        assertThat(group(properties.get("query"))).isEqualTo("main");
        assertThat(group(properties.get("fetchType"))).isEqualTo("processing");
        assertThat(group(properties.get("limit"))).isEqualTo("processing");
        assertThat(group(properties.get("offset"))).isEqualTo("processing");
        assertThat(group(properties.get("fields"))).isEqualTo("processing");
    }

    // Dynamic-renderable non-String properties (e.g. Integer, enum, List) render as an "anyOf" of type
    // variants (typed value + Pebble expression string) instead of a flat schema, so `$group`/`$secret`
    // live on the first "anyOf" entry rather than at the top level.
    @SuppressWarnings("unchecked")
    private static Object group(Map<String, Object> propertySchema) {
        return metadata(propertySchema).get("$group");
    }

    @SuppressWarnings("unchecked")
    private static Object secret(Map<String, Object> propertySchema) {
        return metadata(propertySchema).get("$secret");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(Map<String, Object> propertySchema) {
        if (propertySchema.containsKey("$group") || propertySchema.containsKey("$secret")) {
            return propertySchema;
        }
        var anyOf = (List<Map<String, Object>>) propertySchema.get("anyOf");
        return anyOf.getFirst();
    }
}
