package io.kestra.plugin.servicenow;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
class TransitionChangeTest extends NativeTaskTestSupport {
    private TransitionChange.TransitionChangeBuilder<?, ?> task(WireMockRuntimeInfo server) {
        return connected(TransitionChange.builder(), server, TransitionChange.class).sysId(Property.ofValue(CHANGE));
    }

    private void model(List<String> states, List<String> labels) throws Exception {
        for (var index = 0; index < states.size(); index++) {
            var phase = index == 0 ? Scenario.STARTED : "phase-" + index;
            var fields = index == states.size() - 1 ? Map.<String, Object> of("close_code", "successful", "close_notes", "Verified") : Map.<String, Object> of();
            stubFor(
                get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("model").whenScenarioStateIs(phase)
                    .willReturn(okJson(change(states.get(index), labels.get(index), "approved", fields)))
            );
            var target = Math.min(index + 1, states.size() - 1);
            stubFor(
                get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).inScenario("model").whenScenarioStateIs(phase)
                    .willReturn(okJson(next(states.get(index), labels.get(index), states.get(target), labels.get(target), true)))
            );
            if (index + 1 < states.size()) {
                stubFor(
                    patch(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("model").whenScenarioStateIs(phase)
                        .withRequestBody(matchingJsonPath("$.state", equalTo(states.get(target))))
                        .willReturn(okJson(change(states.get(target), labels.get(target), "approved", fields))).willSetStateTo("phase-" + target)
                );
            }
        }
    }

    @Test
    void shouldCloseThroughVerifiedModelWaypointsAndMakeRerunsNoOp(WireMockRuntimeInfo server) throws Exception {
        model(List.of("-2", "-1", "0", "3"), List.of("Scheduled", "Implement", "Review", "Closed"));
        var task = task(server).to(Property.ofValue("CLOSED")).closeCode(Property.ofValue("successful"))
            .closeNotes(Property.ofValue("Verified")).workNotes(Property.ofValue("Finished once")).build();
        var first = task.run(context(task));
        assertEquals("3", first.getState());
        assertTrue(first.getChanged());
        assertEquals(List.of("-1", "0", "3"), first.getTransitions().stream().map(TransitionChange.Step::getTo).toList());
        assertFalse(task.run(context(task)).getChanged());
        verify(3, patchRequestedFor(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)));
        verify(
            1,
            patchRequestedFor(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE))
                .withRequestBody(equalToJson("{\"state\":\"3\",\"close_code\":\"successful\",\"close_notes\":\"Verified\",\"work_notes\":\"Finished once\"}"))
        );
        verify(0, patchRequestedFor(urlMatching("/api/now/table/.*")));
    }

    @Test
    void shouldResolveCustomStatesWithoutHardcodedStateNumbers(WireMockRuntimeInfo server) throws Exception {
        model(List.of("100", "200", "300", "400"), List.of("Ready", "Deploy", "Validate", "Closed"));
        var task = task(server).to(Property.ofValue("Closed")).via(Property.ofValue(List.of("Deploy", "Validate")))
            .closeCode(Property.ofValue("successful")).closeNotes(Property.ofValue("Verified")).build();
        var result = task.run(context(task));
        assertEquals("400", result.getState());
        assertEquals(List.of("200", "300", "400"), result.getTransitions().stream().map(TransitionChange.Step::getTo).toList());
    }

    @Test
    void shouldFailUnmetConditionsBeforeAnyWrite(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", Map.of()))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).willReturn(okJson(next("-5", "New", "-4", "Assess", false))));
        var task = task(server).to(Property.ofValue("Assess")).build();
        var failure = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(failure.getMessage().contains("Assignment group is not empty"));
        verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldReportFreshConditionsWhenPatchIsRefused(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", Map.of()))));
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).inScenario("refused").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(next("-5", "New", "-4", "Assess", true)))
        );
        stubFor(
            patch(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("refused").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":{\"message\":\"Refused transition\"}}")).willSetStateTo("blocked")
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).inScenario("refused").whenScenarioStateIs("blocked")
                .willReturn(okJson(next("-5", "New", "-4", "Assess", false)))
        );
        var task = task(server).to(Property.ofValue("Assess")).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("Refused transition"));
        assertTrue(error.getMessage().contains("Assignment group is not empty"));
        verify(1, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldFailAnIneffectivePatchDespiteHttp200(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", Map.of()))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).willReturn(okJson(next("-5", "New", "-4", "Assess", true))));
        stubFor(patch(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", Map.of()))));
        var task = task(server).to(Property.ofValue("Assess")).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("did not reach requested state"));
        verify(1, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldStopIfRevisionChangesAfterPlanning(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("revision").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(change("-5", "New", "requested", Map.of("sys_mod_count", 0))))
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).inScenario("revision").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(next("-5", "New", "-4", "Assess", true))).willSetStateTo("next read once")
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("revision").whenScenarioStateIs("next read once")
                .willReturn(okJson(change("-5", "New", "requested", Map.of("sys_mod_count", 0))))
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).inScenario("revision").whenScenarioStateIs("next read once")
                .willReturn(okJson(next("-5", "New", "-4", "Assess", true))).willSetStateTo("changed")
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("revision").whenScenarioStateIs("changed")
                .willReturn(okJson(change("-5", "New", "requested", Map.of("sys_mod_count", 1))))
        );
        var task = task(server).to(Property.ofValue("Assess")).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("changed while planning"));
        verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldKeepNegativeStateValuesDistinctFromCancellation(WireMockRuntimeInfo server) throws Exception {
        model(List.of("-5", "-4"), List.of("New", "Assess"));
        var task = task(server).to(Property.ofValue("-4")).build();
        assertEquals("-4", task.run(context(task)).getState());
        verify(patchRequestedFor(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).withRequestBody(equalToJson("{\"state\":\"-4\"}")));
        verify(0, patchRequestedFor(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).withRequestBody(matchingJsonPath("$.state", equalTo("4"))));
    }

    @Test
    void shouldNotChooseCancellationToCloseANewChange(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "not requested", Map.of()))));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).willReturn(okJson(next("-5", "New", "-4", "Assess", true))));
        var task = task(server).to(Property.ofValue("Closed")).closeCode(Property.ofValue("successful")).closeNotes(Property.ofValue("Verified")).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("unavailable"));
        verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldRejectMalformedOrContradictoryModelTransitions(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-5", "New", "requested", Map.of()))));
        var inconsistent = JacksonMapper.ofJson().readTree(next("-5", "New", "-4", "Assess", false));
        ((com.fasterxml.jackson.databind.node.ObjectNode) inconsistent.path("result").path("state_transitions").get(0).get(0)).put("transition_available", true);
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates")).willReturn(okJson(json(inconsistent))));
        var task = task(server).to(Property.ofValue("Assess")).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("Assignment group is not empty"));
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE + "/nextstates"))
                .willReturn(okJson("{\"result\":{\"available_states\":[\"-5\",\"-4\"],\"state_label\":{\"-5\":\"New\",\"-4\":\"Assess\"},\"state_transitions\":\"bad\"}}"))
        );
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("malformed transition"));
        verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldRejectCyclesAndOversizedRoutesBeforeRequests(WireMockRuntimeInfo server) throws Exception {
        var tasks = List.of(
            task(server).to(Property.ofValue("Assess")).via(Property.ofValue(List.of("Assess"))).build(),
            task(server).to(Property.ofValue("Assess")).via(Property.ofValue(java.util.stream.IntStream.range(0, 32).mapToObj(index -> "phase" + index).toList())).build(),
            task(server).to(Property.ofValue("Closed")).build()
        );
        for (var task : tasks) {
            assertThrows(IllegalArgumentException.class, () -> task.run(context(task)));
        }
        verify(0, anyRequestedFor(anyUrl()));
    }
}
