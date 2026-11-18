package io.kestra.plugin.servicenow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
class ServiceNowNativeTest extends NativeTaskTestSupport {
    @Inject
    ModelValidator validator;

    private LoadImportSet importTask(WireMockRuntimeInfo server) {
        return connected(LoadImportSet.builder(), server, LoadImportSet.class).stagingTable(Property.ofValue("imp_computer"))
            .from(Map.of("name", "web-01")).build();
    }

    @Test
    void shouldUseOAuthForNativeApisWithoutCachingAcrossRuns(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/oauth_token.do")).willReturn(okJson("{\"access_token\":\"native-access-token\"}")));
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withBody(imported("ignored", ""))));
        var task = connected(LoadImportSet.builder(), server, LoadImportSet.class).stagingTable(Property.ofValue("imp_computer"))
            .from(List.of(Map.of("name", "web-01"), Map.of("name", "web-02"), Map.of("name", "web-03"))).clientId(Property.ofValue("native-client"))
            .clientSecret(Property.ofValue("native-client-secret")).build();
        assertEquals(3, task.run(context(task)).getSubmittedRows());
        assertEquals(3, task.run(context(task)).getSubmittedRows());
        verify(2, postRequestedFor(urlEqualTo("/oauth_token.do")));
        verify(6, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")).withHeader("Authorization", equalTo("Bearer native-access-token")));
        assertFalse(task.toString().contains("native-client-secret"));
    }

    @Test
    void shouldClampLargeCoreTimeoutsToTheInternalBudget(WireMockRuntimeInfo server) throws Exception {
        var task = connected(CreateChange.builder(), server, CreateChange.class).shortDescription(Property.ofValue("Patch"))
            .timeout(Property.ofValue(Duration.ofDays(1000000))).build();
        try (var poll = task.begin(context(task), Duration.ofSeconds(30), Duration.ofMinutes(5))) {
            assertTrue(poll.remaining().compareTo(Duration.ofMinutes(5)) <= 0);
            assertTrue(poll.remaining().compareTo(Duration.ofMinutes(4)) > 0);
        }
        for (var timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            var invalid = connected(CreateChange.builder(), server, CreateChange.class).shortDescription(Property.ofValue("Patch"))
                .timeout(Property.ofValue(timeout)).build();
            assertThrows(IllegalArgumentException.class, () -> invalid.begin(context(invalid), Duration.ofSeconds(30), Duration.ofMinutes(5)));
        }
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void shouldRefreshAfter401AndReuseTheNewToken(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            post(urlEqualTo("/oauth_token.do")).inScenario("token refresh").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"access_token\":\"expired-token\"}")).willSetStateTo("issued")
        );
        stubFor(
            post(urlEqualTo("/oauth_token.do")).inScenario("token refresh").whenScenarioStateIs("issued")
                .willReturn(okJson("{\"access_token\":\"fresh-token\"}"))
        );
        stubFor(
            post(urlEqualTo("/api/now/import/imp_computer")).withHeader("Authorization", equalTo("Bearer expired-token"))
                .willReturn(unauthorized().withBody("{\"error\":{\"message\":\"Token expired\"}}"))
        );
        stubFor(
            post(urlEqualTo("/api/now/import/imp_computer")).withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(created().withBody(imported("ignored", "")))
        );
        var task = connected(LoadImportSet.builder(), server, LoadImportSet.class).stagingTable(Property.ofValue("imp_computer"))
            .from(List.of(Map.of("name", "web-01"), Map.of("name", "web-02"))).clientId(Property.ofValue("native-client"))
            .clientSecret(Property.ofValue("native-client-secret")).build();
        assertEquals(2, task.run(context(task)).getSubmittedRows());
        verify(2, postRequestedFor(urlEqualTo("/oauth_token.do")));
        verify(1, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")).withHeader("Authorization", equalTo("Bearer expired-token")));
        verify(2, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")).withHeader("Authorization", equalTo("Bearer fresh-token")));
    }

    @Test
    void shouldFailAfterOneRefreshAndRedactBothTokens(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            post(urlEqualTo("/oauth_token.do")).inScenario("rejected tokens").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"access_token\":\"expired-token\"}")).willSetStateTo("issued")
        );
        stubFor(
            post(urlEqualTo("/oauth_token.do")).inScenario("rejected tokens").whenScenarioStateIs("issued")
                .willReturn(okJson("{\"access_token\":\"fresh-token\"}"))
        );
        stubFor(
            post(urlEqualTo("/api/now/import/imp_computer")).willReturn(
                unauthorized()
                    .withBody(json(Map.of("error", Map.of("message", "Rejected expired-token and fresh-token", "detail", PASSWORD))))
            )
        );
        var task = connected(LoadImportSet.builder(), server, LoadImportSet.class).stagingTable(Property.ofValue("imp_computer"))
            .from(Map.of("name", "web-01")).clientId(Property.ofValue("native-client"))
            .clientSecret(Property.ofValue("native-client-secret")).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("HTTP 401"));
        assertFalse(error.getMessage().contains("expired-token"));
        assertFalse(error.getMessage().contains("fresh-token"));
        assertFalse(error.getMessage().contains(PASSWORD));
        verify(2, postRequestedFor(urlEqualTo("/oauth_token.do")));
        verify(2, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
    }

    @Test
    void shouldNotRefreshOrReplayOtherHttpFailures(WireMockRuntimeInfo server) throws Exception {
        for (var status : List.of(403, 500)) {
            resetAllRequests();
            stubFor(post(urlEqualTo("/oauth_token.do")).willReturn(okJson("{\"access_token\":\"native-access-token\"}")));
            stubFor(
                post(urlEqualTo("/api/now/import/imp_computer")).willReturn(
                    aResponse().withStatus(status)
                        .withBody("{\"error\":{\"message\":\"Request refused\"}}")
                )
            );
            var task = connected(LoadImportSet.builder(), server, LoadImportSet.class).stagingTable(Property.ofValue("imp_computer"))
                .from(Map.of("name", "web-01")).clientId(Property.ofValue("native-client"))
                .clientSecret(Property.ofValue("native-client-secret")).build();
            assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("HTTP %s".formatted(status)));
            verify(1, postRequestedFor(urlEqualTo("/oauth_token.do")));
            verify(1, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
        }
    }

    @Test
    void shouldNotReplayAWriteWhenCancelledDuringTokenRefresh(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            post(urlEqualTo("/oauth_token.do")).inScenario("cancel refresh").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"access_token\":\"expired-token\"}")).willSetStateTo("issued")
        );
        stubFor(
            post(urlEqualTo("/oauth_token.do")).inScenario("cancel refresh").whenScenarioStateIs("issued")
                .willReturn(okJson("{\"access_token\":\"fresh-token\"}").withFixedDelay(1500))
        );
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(unauthorized()));
        var task = connected(LoadImportSet.builder(), server, LoadImportSet.class).stagingTable(Property.ofValue("imp_computer"))
            .from(Map.of("name", "web-01")).clientId(Property.ofValue("native-client"))
            .clientSecret(Property.ofValue("native-client-secret")).build();
        var context = context(task);
        var result = CompletableFuture.supplyAsync(() -> assertThrows(CancellationException.class, () -> task.run(context)));
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(2, postRequestedFor(urlEqualTo("/oauth_token.do"))));
            task.kill();
            assertTrue(result.get(5, TimeUnit.SECONDS).getMessage().contains("cancelled"));
            verify(1, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
        } finally {
            task.kill();
            result.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldNotSubmitAWriteWhenCancelledDuringOAuth(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/oauth_token.do")).willReturn(okJson("{\"access_token\":\"native-access-token\"}").withFixedDelay(1500)));
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withBody(imported("ignored", ""))));
        var task = connected(LoadImportSet.builder(), server, LoadImportSet.class).stagingTable(Property.ofValue("imp_computer"))
            .from(Map.of("name", "web-01")).clientId(Property.ofValue("native-client"))
            .clientSecret(Property.ofValue("native-client-secret")).build();
        var context = context(task);
        var result = java.util.concurrent.CompletableFuture.supplyAsync(() ->
        {
            return assertThrows(java.util.concurrent.CancellationException.class, () -> task.run(context));
        });
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(1, postRequestedFor(urlEqualTo("/oauth_token.do"))));
            task.kill();
            assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS).getMessage().contains("cancelled"));
            verify(0, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
        } finally {
            task.kill();
            result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldRejectEmptyInvalidAndNonObjectResponsesForTheRightReason(WireMockRuntimeInfo server) throws Exception {
        var cases = Map.of("", "empty or non-object", "[]", "empty or non-object", "{broken", "invalid JSON");
        for (var entry : cases.entrySet()) {
            stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(ok().withBody(entry.getKey())));
            var task = importTask(server);
            var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
            assertTrue(error.getMessage().contains(entry.getValue()), error.getMessage());
        }
        verify(3, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
    }

    @Test
    void shouldRejectBusinessErrorsInsideHttp200(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(okJson("{\"status\":\"failure\",\"error\":{\"message\":\"Transform is disabled\"}}")));
        var task = importTask(server);
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("Transform is disabled"));
        verify(1, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
    }

    @Test
    void shouldEnforceTheResponseCeilingBeforeParsing(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(ok().withBody("x".repeat(ServiceNowApi.MAX_RESPONSE_BYTES + 1))));
        var task = importTask(server);
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("4 MiB"), error.getMessage());
        verify(1, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
    }

    @Test
    void shouldRejectWrongRecordIdentityBeforeAuditingApproval(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-2", "Scheduled", "approved", Map.of("sys_id", CI)))));
        var task = connected(WaitForApproval.builder(), server, WaitForApproval.class).sysId(Property.ofValue(CHANGE)).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> task.run(context(task))).getMessage().contains("different sys_id"));
        verify(0, getRequestedFor(urlPathEqualTo("/api/now/table/sysapproval_approver")));
    }

    @Test
    void shouldRejectReferenceQueryInjectionBeforeAnyRequest(WireMockRuntimeInfo server) throws Exception {
        for (var name : List.of("alex^ORactive=true", "javascript:gs.getUserID()", "name\noperator")) {
            var task = connected(CreateChange.builder(), server, CreateChange.class).shortDescription(Property.ofValue("Patch"))
                .assignmentGroup(Property.ofValue(name)).build();
            assertThrows(IllegalArgumentException.class, () -> task.run(context(task)));
        }
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void shouldKeepCredentialsAndInputRecordsOutOfTaskStrings(WireMockRuntimeInfo server) {
        var tasks = List.of(
            importTask(server), connected(CreateChange.builder(), server, CreateChange.class).shortDescription(Property.ofValue("Patch"))
                .data(Property.ofValue(Map.of("description", "private-input"))).build(),
            connected(UpsertCI.builder(), server, UpsertCI.class).values(Property.ofValue(Map.of("name", "private-input"))).build(),
            connected(WaitForApproval.builder(), server, WaitForApproval.class).build(), connected(TransitionChange.builder(), server, TransitionChange.class).build()
        );
        for (var task : tasks) {
            assertFalse(task.toString().contains(PASSWORD));
            assertFalse(task.toString().contains(USERNAME));
            assertFalse(task.toString().contains("private-input"));
        }
    }

    @Test
    void shouldPreserveScalarPrecisionAndRejectNonScalarValues() throws Exception {
        assertEquals(Map.of("ram", "8192", "enabled", "true"), ServiceNowValues.stringValues(Map.of("ram", 8192.0, "enabled", true)));
        ServiceNowValues.verify(JacksonMapper.ofJson().readTree("{\"ram\":{\"value\":8192.0},\"enabled\":{\"value\":true}}"), Map.of("ram", "8192", "enabled", "true"));
        assertThrows(IllegalArgumentException.class, () -> ServiceNowValues.stringValues(Map.of("ram", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> ServiceNowValues.stringValues(Map.of("name", Map.of("nested", true))));
    }

    @Test
    void shouldPreserveIdentifierAndStateSignInvariants() {
        var random = new Random(117);
        var digits = "0123456789abcdef";
        for (var index = 0; index < 200; index++) {
            var id = new StringBuilder();
            for (var digit = 0; digit < 32; digit++) {
                id.append(digits.charAt(random.nextInt(digits.length())));
            }
            var lower = id.toString();
            assertEquals(lower, ServiceNowValues.sysId(lower.toUpperCase(java.util.Locale.ROOT), "id"));
            var state = 1 + random.nextInt(1000);
            assertNotEquals(ServiceNowRecords.normaliseState(Integer.toString(state)), ServiceNowRecords.normaliseState(Integer.toString(-state)));
            assertEquals(Integer.toString(-state), ServiceNowRecords.normaliseState("  " + -state + ".0  "));
        }
    }

    @Test
    void shouldRejectMalformedUtf8AndUnsafeBaseUrls() {
        var api = new ServiceNowApi("http://localhost/", request -> new ServiceNowApi.Response(200, new byte[] { (byte) 0xff, (byte) 0xfe }, Set.of()));
        assertThrows(IllegalStateException.class, () -> api.get("api/test", Map.of()));
        for (var base : List.of("file:///tmp/input", "https://user:" + PASSWORD + "@example.org/", "https://example.org/?token=" + PASSWORD)) {
            var error = assertThrows(
                IllegalArgumentException.class, () -> new ServiceNowApi(base, request -> new ServiceNowApi.Response(200, "{}".getBytes(StandardCharsets.UTF_8), Set.of()))
            );
            assertFalse(error.getMessage().contains(PASSWORD));
        }
    }

    @Test
    void shouldParseAndValidateEveryNewTaskExample() {
        for (var type : List.of(LoadImportSet.class, CreateChange.class, WaitForApproval.class, TransitionChange.class, UpsertCI.class)) {
            for (var example : type.getAnnotation(Plugin.class).examples()) {
                assertTrue(example.full());
                var flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertTrue(validator.isValid(flow).isEmpty(), type.getSimpleName());
            }
        }
    }
}
