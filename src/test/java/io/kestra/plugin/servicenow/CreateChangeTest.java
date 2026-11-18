package io.kestra.plugin.servicenow;

import java.time.Duration;
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
class CreateChangeTest extends NativeTaskTestSupport {
    private CreateChange.CreateChangeBuilder<?, ?> task(WireMockRuntimeInfo server) {
        return connected(CreateChange.builder(), server, CreateChange.class).shortDescription(Property.ofValue("Patch web-01"));
    }

    @Test
    void shouldCreateNormalThroughTheJsonChangeApiAndVerifyFields(WireMockRuntimeInfo server) throws Exception {
        var fields = Map
            .<String, Object> of("short_description", "Patch web-01", "implementation_plan", "Import inventory", "start_date", "2026-11-17 10:00:00", "end_date", "2026-11-17 11:00:00");
        stubFor(post(urlEqualTo("/api/sn_chg_rest/change")).willReturn(okJson(change("-5", "New", "not requested", fields))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", fields))));
        var task = task(server).timeout(Property.ofValue(Duration.ofMinutes(10))).implementationPlan(Property.ofValue("Import inventory"))
            .plannedStart(Property.ofValue("2026-11-17T12:00:00+02:00"))
            .plannedEnd(Property.ofValue("2026-11-17T13:00:00+02:00")).build();
        var output = task.run(context(task));
        assertEquals(CHANGE, output.getSysId());
        assertEquals("CHG001", output.getNumber());
        assertEquals("-5", output.getState());
        assertTrue(output.getUrl().endsWith("change_request.do?sys_id=" + CHANGE));
        verify(
            postRequestedFor(urlEqualTo("/api/sn_chg_rest/change")).withRequestBody(
                equalToJson(
                    "{\"type\":\"normal\",\"short_description\":\"Patch web-01\",\"implementation_plan\":\"Import inventory\",\"start_date\":\"2026-11-17 10:00:00\",\"end_date\":\"2026-11-17 11:00:00\"}"
                )
            )
        );
        verify(0, anyRequestedFor(urlMatching("/api/now/table/change_request.*")));
    }

    @Test
    void shouldCreateEmergencyWithoutUsingTheQueryOnlyEndpoint(WireMockRuntimeInfo server) throws Exception {
        var fields = Map.<String, Object> of("short_description", "Patch web-01", "type", "emergency");
        stubFor(post(urlEqualTo("/api/sn_chg_rest/change")).willReturn(okJson(change("-5", "New", "not requested", fields))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", fields))));
        var task = task(server).model(Property.ofValue(CreateChange.Model.EMERGENCY)).build();
        assertEquals(CHANGE, task.run(context(task)).getSysId());
        verify(postRequestedFor(urlEqualTo("/api/sn_chg_rest/change")).withRequestBody(equalToJson("{\"type\":\"emergency\",\"short_description\":\"Patch web-01\"}")));
        verify(0, postRequestedFor(urlPathEqualTo("/api/sn_chg_rest/change/emergency")));
    }

    @Test
    void shouldCreateStandardFromItsPublishedTemplate(WireMockRuntimeInfo server) throws Exception {
        var record = io.kestra.core.serializers.JacksonMapper.ofJson().readTree(change("-5", "New", "not requested", Map.of("short_description", "Patch web-01", "type", "standard")))
            .get("result");
        stubFor(post(urlPathEqualTo("/api/sn_chg_rest/change/standard/" + TEMPLATE)).willReturn(okJson(json(Map.of("result", List.of(record))))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(json(Map.of("result", record)))));
        var task = task(server).model(Property.ofValue(CreateChange.Model.STANDARD)).standardTemplate(Property.ofValue(TEMPLATE)).build();
        assertEquals(CHANGE, task.run(context(task)).getSysId());
        verify(postRequestedFor(urlPathEqualTo("/api/sn_chg_rest/change/standard/" + TEMPLATE)).withQueryParam("short_description", equalTo("Patch web-01")));
        verify(0, postRequestedFor(urlEqualTo("/api/sn_chg_rest/change")));
    }

    @Test
    void shouldRejectOversizedEncodedStandardOverridesBeforeAnyRequest(WireMockRuntimeInfo server) {
        var task = task(server).model(Property.ofValue(CreateChange.Model.STANDARD)).standardTemplate(Property.ofValue(TEMPLATE))
            .justification(Property.ofValue("é".repeat(400))).build();
        var error = assertThrows(IllegalArgumentException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("2048"));
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void shouldRejectStandardFieldsOwnedByThePublishedTemplate(WireMockRuntimeInfo server) {
        for (var field : List.of("description", "implementation_plan", "backout_plan", "test_plan")) {
            var task = task(server).model(Property.ofValue(CreateChange.Model.STANDARD)).standardTemplate(Property.ofValue(TEMPLATE))
                .data(Property.ofValue(Map.of(field, "Template-owned content"))).build();
            assertTrue(assertThrows(IllegalArgumentException.class, () -> task.run(context(task))).getMessage().contains("published template"));
        }
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void shouldRequestAssessAndConfirmGeneratedApprovalRecords(WireMockRuntimeInfo server) throws Exception {
        var fields = Map.<String, Object> of("short_description", "Patch web-01", "assignment_group", GROUP);
        stubFor(post(urlEqualTo("/api/sn_chg_rest/change")).willReturn(okJson(change("-5", "New", "not requested", fields))));
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("request approval").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(change("-5", "New", "not requested", fields)))
        );
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).willReturn(okJson(next("-5", "New", "-4", "Assess", true))));
        stubFor(
            patch(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("request approval").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(change("-4", "Assess", "requested", fields))).willSetStateTo("assess")
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("request approval").whenScenarioStateIs("assess").willReturn(okJson(change("-4", "Assess", "requested", fields)))
        );
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).willReturn(okJson(audit("requested"))));
        var task = task(server).assignmentGroup(Property.ofValue(GROUP)).requestApproval(Property.ofValue(true)).build();
        var output = task.run(context(task));
        assertEquals("-4", output.getState());
        assertEquals("requested", output.getApproval());
        verify(patchRequestedFor(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).withRequestBody(equalToJson("{\"state\":\"-4\"}")));
        verify(getRequestedFor(urlPathEqualTo("/api/now/table/sysapproval_approver")).withQueryParam("sysparm_query", equalTo("sysapproval=" + CHANGE + "^ORDERBYsys_id")));
        verify(0, patchRequestedFor(urlPathEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/approvals")));
        verify(0, anyRequestedFor(urlMatching("/api/now/table/change_request.*")));
    }

    @Test
    void shouldResolveGroupCiAndModelNamesBeforeCreating(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlPathEqualTo("/api/now/table/sys_user_group")).willReturn(okJson(json(Map.of("result", List.of(Map.of("sys_id", GROUP, "name", "Change Management")))))));
        stubFor(get(urlPathEqualTo("/api/now/table/cmdb_ci")).willReturn(okJson(json(Map.of("result", List.of(Map.of("sys_id", CI, "name", "web-01")))))));
        stubFor(get(urlPathEqualTo("/api/sn_chg_rest/change/model")).willReturn(okJson(json(Map.of("result", List.of(Map.of("sys_id", MODEL, "name", "Production")))))));
        var fields = Map.<String, Object> of("short_description", "Patch web-01", "assignment_group", GROUP, "cmdb_ci", CI, "chg_model", MODEL);
        stubFor(post(urlEqualTo("/api/sn_chg_rest/change")).willReturn(okJson(change("-5", "New", "not requested", fields))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", fields))));
        var task = task(server).assignmentGroup(Property.ofValue("Change Management")).configurationItem(Property.ofValue("web-01")).changeModel(Property.ofValue("Production")).build();
        assertEquals(CHANGE, task.run(context(task)).getSysId());
        verify(
            postRequestedFor(urlEqualTo("/api/sn_chg_rest/change"))
                .withRequestBody(equalToJson(json(Map.of("type", "normal", "short_description", "Patch web-01", "assignment_group", GROUP, "cmdb_ci", CI, "chg_model", "Production"))))
        );
    }

    @Test
    void shouldResolveAModelIdToItsApiNameAndVerifyTheStoredReference(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/model/" + MODEL)).willReturn(okJson(json(Map.of("result", Map.of("sys_id", MODEL, "name", "Production"))))));
        stubFor(
            get(urlPathEqualTo("/api/sn_chg_rest/change/model")).withQueryParam("sysparm_query", equalTo("name=Production"))
                .willReturn(okJson(json(Map.of("result", List.of(Map.of("sys_id", MODEL, "name", "Production"))))))
        );
        var fields = Map.<String, Object> of("short_description", "Patch web-01", "chg_model", MODEL);
        stubFor(post(urlEqualTo("/api/sn_chg_rest/change")).willReturn(okJson(change("-5", "New", "not requested", fields))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", fields))));
        var task = task(server).changeModel(Property.ofValue(MODEL)).build();
        assertEquals(CHANGE, task.run(context(task)).getSysId());
        verify(postRequestedFor(urlEqualTo("/api/sn_chg_rest/change")).withRequestBody(matchingJsonPath("$.chg_model", equalTo("Production"))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", Map.of("short_description", "Patch web-01", "chg_model", CI)))));
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("did not persist field chg_model"));
    }

    @Test
    void shouldRejectAmbiguousReferencesBeforeCreation(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            get(urlPathEqualTo("/api/now/table/sys_user_group"))
                .willReturn(okJson(json(Map.of("result", List.of(Map.of("sys_id", GROUP, "name", "Change Management"), Map.of("sys_id", USER, "name", "Change Management"))))))
        );
        var task = task(server).assignmentGroup(Property.ofValue("Change Management")).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("multiple"));
        verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void shouldFailDroppedFieldsBeforeRequestingApproval(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/api/sn_chg_rest/change")).willReturn(okJson(change("-5", "New", "not requested", Map.of("short_description", "Wrong value")))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", Map.of("short_description", "Wrong value")))));
        var task = task(server).requestApproval(Property.ofValue(true)).assignmentGroup(Property.ofValue(GROUP)).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains(CHANGE));
        assertTrue(error.getMessage().contains("did not persist"));
        verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldSurfaceUnmetModelConditionsWithoutPatching(WireMockRuntimeInfo server) throws Exception {
        var fields = Map.<String, Object> of("short_description", "Patch web-01", "assignment_group", GROUP);
        stubFor(post(urlEqualTo("/api/sn_chg_rest/change")).willReturn(okJson(change("-5", "New", "not requested", fields))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", fields))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).willReturn(okJson(next("-5", "New", "-4", "Assess", false))));
        var task = task(server).assignmentGroup(Property.ofValue(GROUP)).requestApproval(Property.ofValue(true)).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("Assignment group is not empty"));
        verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldFailWhenApprovalRecordsAreNeverGenerated(WireMockRuntimeInfo server) throws Exception {
        var fields = Map.<String, Object> of("short_description", "Patch web-01", "assignment_group", GROUP);
        stubFor(post(urlEqualTo("/api/sn_chg_rest/change")).willReturn(okJson(change("-5", "New", "not requested", fields))));
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("empty approvals").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(change("-5", "New", "not requested", fields)))
        );
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).willReturn(okJson(next("-5", "New", "-4", "Assess", true))));
        stubFor(patch(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-4", "Assess", "requested", fields))).inScenario("empty approvals").willSetStateTo("assess"));
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("empty approvals").whenScenarioStateIs("assess").willReturn(okJson(change("-4", "Assess", "requested", fields)))
        );
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).willReturn(okJson("{\"result\":[]}")));
        var task = task(server).assignmentGroup(Property.ofValue(GROUP)).requestApproval(Property.ofValue(true)).timeout(Property.ofValue(Duration.ofSeconds(1))).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("timeout"), error.getMessage());
    }

    @Test
    void shouldRejectInvalidInputsAndReservedFieldsBeforeCalls(WireMockRuntimeInfo server) throws Exception {
        var tasks = List.of(
            task(server).model(Property.ofValue(CreateChange.Model.STANDARD)).build(),
            task(server).standardTemplate(Property.ofValue(TEMPLATE)).build(), task(server).shortDescription(Property.ofValue(" ")).build(),
            task(server).plannedStart(Property.ofValue("2026-11-17 11:00:00")).plannedEnd(Property.ofValue("2026-11-17 10:00:00")).build(),
            task(server).data(Property.ofValue(Map.of("approval", "approved"))).build(), task(server).requestApproval(Property.ofValue(true)).build()
        );
        for (var task : tasks) {
            assertThrows(IllegalArgumentException.class, () -> task.run(context(task)));
        }
        verify(0, anyRequestedFor(anyUrl()));
    }
}
