package io.kestra.plugin.servicenow;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.JacksonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
class WaitForApprovalTest extends NativeTaskTestSupport {
    private WaitForApproval.WaitForApprovalBuilder<?, ?> task(WireMockRuntimeInfo server) {
        return connected(WaitForApproval.builder(), server, WaitForApproval.class).sysId(Property.ofValue(CHANGE)).interval(Property.ofValue(Duration.ofMillis(10)));
    }

    @Test
    void shouldWaitAndReturnEveryReadableApproverDecision(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("approval").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(change("-3", "Authorize", "requested", Map.of()))).willSetStateTo("approved")
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("approval").whenScenarioStateIs("approved")
                .willReturn(okJson(change("-2", "Scheduled", "approved", Map.of())))
        );
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).willReturn(okJson(audit("approved"))));
        var task = task(server).build();
        var output = task.run(context(task));
        assertEquals(WaitForApproval.Approval.APPROVED, output.getApproval());
        assertEquals("-2", output.getState());
        assertEquals("Avery Example", output.getApprovers().getFirst().getName());
        assertEquals("avery@example.org", output.getApprovers().getFirst().getEmail());
        assertEquals("Reviewed the implementation plan", output.getApprovers().getFirst().getComments());
        assertEquals(Instant.parse("2026-11-17T09:00:00Z"), output.getApprovers().getFirst().getUpdatedAt());
        verify(0, anyRequestedFor(urlMatching("/api/now/table/change_request.*")));
        verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldFailRejectionWithAStoredAudit(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-3", "Authorize", "rejected", Map.of()))));
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).willReturn(okJson(audit("rejected"))));
        var task = task(server).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("rejected"));
        assertTrue(error.getMessage().contains("kestra://"));
        var allowed = task(server).failOnRejection(Property.ofValue(false)).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build();
        var output = allowed.run(context(allowed));
        assertEquals(WaitForApproval.Approval.REJECTED, output.getApproval());
        assertEquals("rejected", output.getApprover().getState());
        assertNull(output.getApprovers());
    }

    @Test
    void shouldStoreAuditAndReturnCountsOnlyWhenRequested(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-2", "Scheduled", "approved", Map.of()))));
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).willReturn(okJson(audit("approved"))));
        var task = task(server).fetchType(Property.ofValue(FetchType.STORE)).build();
        var context = context(task);
        var output = task.run(context);
        assertNull(output.getApprovers());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals("Avery Example", JacksonMapper.ofIon().readValue(input, Map.class).get("name"));
        }
        var none = task(server).fetchType(Property.ofValue(FetchType.NONE)).build();
        var count = none.run(context(none));
        assertEquals(1, count.getSize());
        assertNull(count.getApprovers());
        assertNull(count.getApprover());
        assertNull(count.getUri());
    }

    @Test
    void shouldFailTimeoutInsteadOfFallingThrough(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-3", "Authorize", "requested", Map.of()))));
        var task = task(server).timeout(Property.ofValue(Duration.ofMillis(300))).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("timeout"), error.getMessage());
        verify(0, postRequestedFor(anyUrl()));
        verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void shouldRejectCancelledChangesEvenWithHistoricalApproval(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("4", "Canceled", "approved", Map.of()))));
        var task = task(server).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("change was cancelled"));
        verify(0, getRequestedFor(urlPathEqualTo("/api/now/table/sysapproval_approver")));
    }

    @Test
    void shouldRecheckApprovalAfterReadingItsAudit(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("revocation").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(change("-2", "Scheduled", "approved", Map.of()))).willSetStateTo("auditing")
        );
        stubFor(
            get(urlPathEqualTo("/api/now/table/sysapproval_approver")).inScenario("revocation").whenScenarioStateIs("auditing")
                .willReturn(okJson(audit("approved"))).willSetStateTo("revoked")
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("revocation").whenScenarioStateIs("revoked")
                .willReturn(okJson(change("-3", "Authorize", "requested", Map.of()))).willSetStateTo("approved again")
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("revocation").whenScenarioStateIs("approved again")
                .willReturn(okJson(change("-2", "Scheduled", "approved", Map.of())))
        );
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).inScenario("revocation").whenScenarioStateIs("approved again").willReturn(okJson(audit("approved"))));
        var task = task(server).build();
        assertEquals(WaitForApproval.Approval.APPROVED, task.run(context(task)).getApproval());
        verify(4, getRequestedFor(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)));
        verify(2, getRequestedFor(urlPathEqualTo("/api/now/table/sysapproval_approver")));
    }

    @Test
    void shouldHonorAnInstanceSpecificApprovedState(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("required state").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(change("-4", "Assess", "approved", Map.of()))).willSetStateTo("scheduled")
        );
        stubFor(
            get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).inScenario("required state").whenScenarioStateIs("scheduled")
                .willReturn(okJson(change("-2", "Scheduled", "approved", Map.of())))
        );
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).willReturn(okJson(audit("approved"))));
        var task = task(server).approvedState(Property.ofValue("Scheduled")).build();
        assertEquals("-2", task.run(context(task)).getState());
        verify(3, getRequestedFor(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)));
    }

    @Test
    void shouldRejectMissingAuditUnknownApprovalAndWrongChange(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-2", "Scheduled", "approved", Map.of()))));
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).willReturn(okJson("{\"result\":[]}")));
        var missing = task(server).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> missing.run(context(missing))).getMessage().contains("no readable audit"));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-3", "Authorize", "unrecognised", Map.of()))));
        var unknown = task(server).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> unknown.run(context(unknown))).getMessage().contains("unknown or missing approval"));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-2", "Scheduled", "approved", Map.of()))));
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).willReturn(okJson(audit("approved").replace(CHANGE, CI))));
        var wrong = task(server).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> wrong.run(context(wrong))).getMessage().contains("another change"));
    }

    @Test
    void shouldWakeFromLongIntervalsWhenKilled(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-3", "Authorize", "requested", Map.of()))));
        var task = task(server).interval(Property.ofValue(Duration.ofMinutes(5))).build();
        var future = CompletableFuture.supplyAsync(() ->
        {
            try {
                return task.run(context(task));
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(1, getRequestedFor(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE))));
        assertTimeoutPreemptively(Duration.ofMillis(500), task::kill);
        var failure = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("cancelled"));
        verify(1, getRequestedFor(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)));
    }

    @Test
    void shouldRenderIdsAndRejectInvalidBoundsBeforeRequests(WireMockRuntimeInfo server) throws Exception {
        var interval = task(server).interval(Property.ofValue(Duration.ZERO)).build();
        assertThrows(IllegalArgumentException.class, () -> interval.run(context(interval)));
        var maximum = task(server).maxApprovers(Property.ofValue(0)).build();
        assertThrows(jakarta.validation.ConstraintViolationException.class, () -> maximum.run(context(maximum)));
        var invalid = task(server).sysId(Property.ofValue("../other")).build();
        assertThrows(IllegalArgumentException.class, () -> invalid.run(context(invalid)));
        verify(0, anyRequestedFor(anyUrl()));
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-2", "Scheduled", "approved", Map.of()))));
        stubFor(get(urlPathEqualTo("/api/now/table/sysapproval_approver")).willReturn(okJson(audit("approved"))));
        var rendered = task(server).sysId(Property.ofExpression("{{ changeId }}")).build();
        assertEquals(WaitForApproval.Approval.APPROVED, rendered.run(runContextFactory.of(rendered, Map.of("changeId", CHANGE))).getApproval());
    }
}
