package io.kestra.plugin.servicenow;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.models.property.Property;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
class UpsertCITest extends NativeTaskTestSupport {
    private UpsertCI.UpsertCIBuilder<?, ?> task(WireMockRuntimeInfo server) {
        return connected(UpsertCI.builder(), server, UpsertCI.class).className(Property.ofValue("cmdb_ci_linux_server"))
            .dataSource(Property.ofValue("Kestra")).values(Property.ofValue(Map.of("name", "web-01", "ip_address", "10.20.0.11")));
    }

    private String ci(Map<String, Object> values) throws Exception {
        var record = new LinkedHashMap<String, Object>(Map.of("sys_id", CI, "sys_class_name", "cmdb_ci_linux_server"));
        record.putAll(values);
        return json(Map.of("result", record));
    }

    private void readCi(Map<String, Object> values) throws Exception {
        for (var table : List.of("cmdb_ci", "cmdb_ci_linux_server")) {
            stubFor(get(urlPathEqualTo("/api/now/table/" + table + "/" + CI)).withQueryParam("sysparm_display_value", equalTo("false")).willReturn(okJson(ci(values))));
        }
    }

    @Test
    void shouldUpdateThroughIreAndVerifyBeforeAndAfter(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(ire("UPDATE", CI))));
        var before = ci(Map.of("name", "web-01", "ip_address", "10.20.0.10"));
        var after = ci(Map.of("name", "web-01", "ip_address", "10.20.0.11"));
        for (var table : List.of("cmdb_ci", "cmdb_ci_linux_server")) {
            stubFor(
                get(urlPathEqualTo("/api/now/table/" + table + "/" + CI)).withQueryParam("sysparm_display_value", equalTo("false")).inScenario("ire write")
                    .whenScenarioStateIs(Scenario.STARTED).willReturn(okJson(before))
            );
            stubFor(
                get(urlPathEqualTo("/api/now/table/" + table + "/" + CI)).withQueryParam("sysparm_display_value", equalTo("false")).inScenario("ire write").whenScenarioStateIs("updated")
                    .willReturn(okJson(after))
            );
        }
        stubFor(
            post(urlPathEqualTo("/api/now/identifyreconcile")).inScenario("ire write").whenScenarioStateIs(Scenario.STARTED).willReturn(okJson(ire("UPDATE", CI))).willSetStateTo("updated")
        );
        var task = task(server).timeout(Property.ofValue(Duration.ofMinutes(15))).sourceNativeKey(Property.ofValue("inventory-web-01")).build();
        var output = task.run(context(task));
        assertEquals("UPDATE", output.getOperation());
        assertEquals(CI, output.getSysId());
        assertEquals("10.20.0.10", output.getBefore().get("ip_address"));
        assertEquals("10.20.0.11", output.getAfter().get("ip_address"));
        assertTrue(output.getVerified());
        verify(
            postRequestedFor(urlPathEqualTo("/api/now/identifyreconcile")).withQueryParam("sysparm_data_source", equalTo("Kestra"))
                .withRequestBody(
                    equalToJson(
                        "{\"items\":[{\"className\":\"cmdb_ci_linux_server\",\"values\":{\"name\":\"web-01\",\"ip_address\":\"10.20.0.11\"},\"sys_object_source_info\":{\"source_name\":\"Kestra\",\"source_native_key\":\"inventory-web-01\"}}]}"
                    )
                )
        );
        verify(0, postRequestedFor(urlMatching("/api/now/table/.*")));
        verify(0, patchRequestedFor(urlMatching("/api/now/table/.*")));
    }

    @Test
    void shouldInsertWithoutInventingAnExistingBeforeRecord(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(ire("INSERT", "Unknown"))));
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile")).willReturn(okJson(ire("INSERT", CI))));
        readCi(Map.of("name", "web-01", "ip_address", "10.20.0.11"));
        var task = task(server).build();
        var result = task.run(context(task));
        assertEquals("INSERT", result.getOperation());
        assertTrue(result.getBefore().isEmpty());
        assertTrue(result.getVerified());
        verify(1, postRequestedFor(urlPathEqualTo("/api/now/identifyreconcile")));
    }

    @Test
    void shouldPreviewAnInsertWithoutCommitting(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(ire("INSERT", "Unknown"))));
        var task = task(server).dryRun(Property.ofValue(true)).build();
        var result = task.run(context(task));
        assertFalse(result.getVerified());
        assertNull(result.getSysId());
        assertEquals("10.20.0.11", result.getAfter().get("ip_address"));
        verify(0, postRequestedFor(urlPathEqualTo("/api/now/identifyreconcile")));
        verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void shouldPreserveNoChangeAndReclassificationOperations(WireMockRuntimeInfo server) throws Exception {
        readCi(Map.of("name", "web-01", "ip_address", "10.20.0.11"));
        for (var operation : List.of("NO_CHANGE", "UPDATE_WITH_UPGRADE", "UPDATE_WITH_DOWNGRADE", "UPDATE_WITH_SWITCH")) {
            stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(ire(operation, CI))));
            stubFor(post(urlPathEqualTo("/api/now/identifyreconcile")).willReturn(okJson(ire(operation, CI))));
            var task = task(server).build();
            assertEquals(operation, task.run(context(task)).getOperation());
        }
    }

    @Test
    void shouldFailPerItemErrorsEvenOnHttp200(WireMockRuntimeInfo server) throws Exception {
        var bad = Map.of(
            "result", Map.of(
                "items", List.of(
                    Map.of(
                        "className", "cmdb_ci_linux_server", "operation", "UPDATE", "sysId", CI, "errorCount", 1,
                        "errors", List.of(Map.of("error", "MULTI_MATCH", "message", "Multiple CIs matched"))
                    )
                )
            )
        );
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(json(bad))));
        var preview = task(server).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> preview.run(context(preview))).getMessage().contains("Multiple CIs matched"));
        verify(0, postRequestedFor(urlPathEqualTo("/api/now/identifyreconcile")));
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(ire("UPDATE", CI))));
        readCi(Map.of("name", "web-01", "ip_address", "10.20.0.11"));
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile")).willReturn(okJson(json(bad))));
        var write = task(server).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> write.run(context(write))).getMessage().contains("write reported item errors"));
    }

    @Test
    void shouldFailWhenReconciliationSilentlyDropsAField(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(ire("UPDATE", CI))));
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile")).willReturn(okJson(ire("UPDATE", CI))));
        readCi(Map.of("name", "web-01", "ip_address", "10.20.0.10"));
        var task = task(server).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("did not persist field ip_address"));
        verify(1, postRequestedFor(urlPathEqualTo("/api/now/identifyreconcile")));
    }

    @Test
    void shouldResolveOwnerReferencesAndVerifyWrappedValues(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlPathEqualTo("/api/now/table/sys_user")).willReturn(okJson(json(Map.of("result", List.of(Map.of("sys_id", USER, "email", "avery@example.org")))))));
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(ire("UPDATE", CI))));
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile")).willReturn(okJson(ire("UPDATE", CI))));
        readCi(Map.of("name", "web-01", "owned_by", Map.of("link", "https://example.org/user/" + USER, "value", USER)));
        var task = task(server).values(Property.ofValue(Map.of("name", "web-01", "owned_by", "avery@example.org"))).build();
        assertTrue(task.run(context(task)).getVerified());
        verify(postRequestedFor(urlPathEqualTo("/api/now/identifyreconcile")).withRequestBody(matchingJsonPath("$.items[0].values.owned_by", equalTo(USER))));
    }

    @Test
    void shouldRejectAmbiguousReferencesWithoutAnyIreWrite(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            get(urlPathEqualTo("/api/now/table/sys_user"))
                .willReturn(okJson(json(Map.of("result", List.of(Map.of("sys_id", USER, "email", "avery@example.org"), Map.of("sys_id", APPROVER, "email", "avery@example.org"))))))
        );
        var task = task(server).values(Property.ofValue(Map.of("name", "web-01", "owned_by", "avery@example.org"))).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("multiple"));
        verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void shouldRecheckRevokedApprovalImmediatelyBeforeCommit(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(ire("UPDATE", CI))));
        readCi(Map.of("name", "web-01", "ip_address", "10.20.0.11"));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-2", "Scheduled", "rejected", Map.of()))));
        var task = task(server).changeSysId(Property.ofValue(CHANGE)).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("no longer approved"));
        verify(1, postRequestedFor(urlPathEqualTo("/api/now/identifyreconcile/query")));
        verify(0, postRequestedFor(urlPathEqualTo("/api/now/identifyreconcile")));
    }

    @Test
    void shouldRejectMalformedCountsWrongClassesAndMissingIds(WireMockRuntimeInfo server) throws Exception {
        var cases = Map.of(
            "{}", "exactly one item", "{\"result\":{\"items\":[{\"operation\":\"UPDATE\",\"className\":\"cmdb_ci_linux_server\",\"errorCount\":\"not a number\"}]}}", "malformed errorCount",
            "{\"result\":{\"items\":[{\"operation\":\"UPDATE\",\"className\":\"sys_user\",\"errorCount\":0,\"sysId\":\"" + CI + "\"}]}}", "another class",
            "{\"result\":{\"items\":[{\"operation\":\"UPDATE\",\"className\":\"cmdb_ci_linux_server\",\"errorCount\":0,\"sysId\":\"Unknown\"}]}}", "sys_id"
        );
        for (var entry : cases.entrySet()) {
            stubFor(post(urlPathEqualTo("/api/now/identifyreconcile/query")).willReturn(okJson(entry.getKey())));
            var task = task(server).build();
            var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
            assertTrue(error.getMessage().contains(entry.getValue()), error.getMessage());
        }
        verify(0, postRequestedFor(urlPathEqualTo("/api/now/identifyreconcile")));
    }

    @Test
    void shouldValidateSourceValuesAndIdentifiersBeforeRequests(WireMockRuntimeInfo server) throws Exception {
        var tasks = List.of(
            task(server).dataSource(Property.ofValue(" ")).build(), task(server).className(Property.ofValue("../table")).build(),
            task(server).values(Property.ofValue(Map.of("name", List.of("nested")))).build(), task(server).changeSysId(Property.ofValue("not an id")).build()
        );
        for (var task : tasks) {
            assertThrows(IllegalArgumentException.class, () -> task.run(context(task)));
        }
        verify(0, anyRequestedFor(anyUrl()));
    }
}
