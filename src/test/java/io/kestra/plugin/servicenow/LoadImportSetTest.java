package io.kestra.plugin.servicenow;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.BasicCredentials;
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
class LoadImportSetTest extends NativeTaskTestSupport {
    private LoadImportSet.LoadImportSetBuilder<?, ?> task(WireMockRuntimeInfo server) {
        return connected(LoadImportSet.builder(), server, LoadImportSet.class)
            .stagingTable(Property.ofValue("imp_computer")).from(Map.of("name", "web-01", "serial_number", "ABC123", "ram", 8192));
    }

    @Test
    void shouldSendScalarStringsAndStoreTransformResults(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withBody(imported("inserted", ""))));
        var task = task(server).build();
        var context = context(task);
        var output = task.run(context);
        assertEquals(1, output.getSubmittedRows());
        assertEquals(1, output.getCounts().get(LoadImportSet.Status.INSERTED));
        assertNull(output.getRows());
        try (var input = context.storage().getFile(output.getUri())) {
            var row = JacksonMapper.ofIon().readValue(input, Map.class);
            assertEquals("INSERTED", row.get("status"));
            assertEquals(CI, row.get("sysId"));
            assertEquals("Computer", row.get("transformMap"));
        }
        verify(
            postRequestedFor(urlEqualTo("/api/now/import/imp_computer")).withBasicAuth(new BasicCredentials(USERNAME, PASSWORD))
                .withRequestBody(equalToJson("{\"name\":\"web-01\",\"serial_number\":\"ABC123\",\"ram\":\"8192\"}"))
        );
        verify(0, postRequestedFor(urlMatching("/api/now/table/.*")));
    }

    @Test
    void shouldReportInsertedIgnoredAndUpdatedAgainstTheSameTarget(WireMockRuntimeInfo server) throws Exception {
        var path = "/api/now/import/imp_computer";
        stubFor(post(urlEqualTo(path)).inScenario("row sequence").whenScenarioStateIs(Scenario.STARTED).willReturn(created().withBody(imported("inserted", ""))).willSetStateTo("inserted"));
        stubFor(
            post(urlEqualTo(path)).inScenario("row sequence").whenScenarioStateIs("inserted").willReturn(created().withBody(imported("ignored", "No field values changed")))
                .willSetStateTo("ignored")
        );
        stubFor(post(urlEqualTo(path)).inScenario("row sequence").whenScenarioStateIs("ignored").willReturn(created().withBody(imported("updated", ""))));
        var rows = List.of(Map.of("serial_number", "ABC123", "ram", 8192), Map.of("serial_number", "ABC123", "ram", 8192), Map.of("serial_number", "ABC123", "ram", 16384));
        var task = task(server).from(rows).fetchType(Property.ofValue(FetchType.FETCH)).build();
        var output = task.run(context(task));
        assertEquals(3, output.getSubmittedRows());
        assertEquals(
            List.of(LoadImportSet.Status.INSERTED, LoadImportSet.Status.IGNORED, LoadImportSet.Status.UPDATED), output.getRows().stream().map(LoadImportSet.Result::getStatus).toList()
        );
        assertEquals(List.of(CI, CI, CI), output.getRows().stream().map(LoadImportSet.Result::getSysId).toList());
        assertEquals(List.of(0, 1, 2), output.getRows().stream().map(LoadImportSet.Result::getInputIndex).toList());
    }

    @Test
    void shouldFailHttp201WithAnErrorAndKeepAllMapResults(WireMockRuntimeInfo server) throws Exception {
        var result = Map.of(
            "import_set", "ISET001", "result", List.of(
                Map.of("status", "inserted", "sys_id", CI, "table", "cmdb_ci_computer", "transform_map", "Computer"),
                Map.of("status", "error", "error_message", "coalesce serial_number is missing", "transform_map", "Secondary")
            )
        );
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withBody(json(result))));
        var task = task(server).from(List.of(Map.of("name", "one"), Map.of("name", "two"))).build();
        var context = context(task);
        var error = assertThrows(IllegalStateException.class, () -> task.run(context));
        assertTrue(error.getMessage().contains("coalesce serial_number"));
        var uri = partialUri(error.getMessage());
        try (var input = context.storage().getFile(uri); var iterator = JacksonMapper.ofIon().readerFor(Map.class).readValues(input)) {
            assertEquals(List.of("INSERTED", "ERROR"), iterator.readAll().stream().map(row -> ((Map<?, ?>) row).get("status")).toList());
        }
        verify(1, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
    }

    @Test
    void shouldCompleteRemainingRowsWhenFailFastIsFalse(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withBody(imported("error", "invalid row"))));
        var task = task(server).from(List.of(Map.of("name", "one"), Map.of("name", "two"))).failFast(Property.ofValue(false)).build();
        var context = context(task);
        var error = assertThrows(IllegalStateException.class, () -> task.run(context));
        try (var input = context.storage().getFile(partialUri(error.getMessage())); var iterator = JacksonMapper.ofIon().readerFor(Map.class).readValues(input)) {
            assertEquals(2, iterator.readAll().size());
        }
        verify(2, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
    }

    @Test
    void shouldHonorExplicitFailOnAndFetchModes(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withBody(imported("error", "expected policy outcome"))));
        for (var fetch : List.of(FetchType.FETCH, FetchType.FETCH_ONE, FetchType.NONE)) {
            var task = task(server).failOn(Property.ofValue(List.of())).fetchType(Property.ofValue(fetch)).build();
            var output = task.run(context(task));
            assertEquals(1, output.getCounts().get(LoadImportSet.Status.ERROR));
            assertNull(output.getUri());
            if (fetch == FetchType.FETCH) {
                assertEquals(1, output.getRows().size());
                assertNull(output.getRow());
            } else if (fetch == FetchType.FETCH_ONE) {
                assertEquals(LoadImportSet.Status.ERROR, output.getRow().getStatus());
                assertNull(output.getRows());
            } else {
                assertNull(output.getRows());
                assertNull(output.getRow());
            }
        }
    }

    @Test
    void shouldValidateTheEntireInputBeforeWriting(WireMockRuntimeInfo server) throws Exception {
        for (var input : List.of("[]", "null", "[42]", "{broken", List.of(Map.of("name", "valid"), Map.of("ram", Map.of("nested", 1))))) {
            var task = task(server).from(input).build();
            assertThrows(IllegalArgumentException.class, () -> task.run(context(task)));
        }
        var invalid = new LinkedHashMap<String, Object>();
        invalid.put("name", null);
        var nullTask = task(server).from(invalid).build();
        assertThrows(IllegalArgumentException.class, () -> nullTask.run(context(nullTask)));
        var tooMany = task(server).maxRows(Property.ofValue(1)).from(List.of(Map.of("name", "one"), Map.of("name", "two"))).build();
        assertThrows(IllegalArgumentException.class, () -> tooMany.run(context(tooMany)));
        var invalidLimit = task(server).maxRows(Property.ofValue(0)).build();
        assertThrows(jakarta.validation.ConstraintViolationException.class, () -> invalidLimit.run(context(invalidLimit)));
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void shouldReadIonDataWithoutRenderingItsContent(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withBody(imported("ignored", ""))));
        var task = task(server).build();
        var context = context(task);
        var file = context.workingDir().createTempFile(".ion");
        JacksonMapper.ofIon().writeValue(file.toFile(), Map.of("name", "{{ literal }}"));
        var uri = context.storage().putFile(file.toFile());
        var fromFile = task(server).from(uri.toString()).build();
        fromFile.run(context);
        verify(postRequestedFor(urlEqualTo("/api/now/import/imp_computer")).withRequestBody(equalToJson("{\"name\":\"{{ literal }}\"}")));
    }

    @Test
    void shouldRejectMalformedAndUnknownTransformResponses(WireMockRuntimeInfo server) throws Exception {
        var cases = Map.of(
            "{}", "no transform results", "{\"result\":[]}", "no transform results", "{\"result\":[42]}", "must be an object",
            "{\"result\":[{\"status\":\"unexpected\"}]}", "unknown or missing transform status", "{\"result\":[{\"status\":\"inserted\"}]}", "sys_id"
        );
        for (var entry : cases.entrySet()) {
            stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withBody(entry.getKey())));
            var task = task(server).build();
            var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
            assertTrue(error.getMessage().contains(entry.getValue()), error.getMessage());
        }
        verify(5, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
    }

    @Test
    void shouldPreserveHttpFailureDetailWithoutCredentials(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            post(urlEqualTo("/api/now/import/imp_computer")).willReturn(aResponse().withStatus(403).withBody(json(Map.of("error", Map.of("message", "Missing import permission " + PASSWORD)))))
        );
        var task = task(server).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("Missing import permission"));
        assertFalse(error.getMessage().contains(PASSWORD));
        assertFalse(task.toString().contains(PASSWORD));
        assertFalse(task.toString().contains(USERNAME));
    }

    @Test
    void shouldStopBeforeTheNextWriteWhenApprovalIsRevoked(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/api/sn_chg_rest/change/" + CHANGE)).willReturn(okJson(change("-2", "Scheduled", "rejected", Map.of()))));
        var task = task(server).changeSysId(Property.ofValue(CHANGE)).build();
        var error = assertThrows(IllegalStateException.class, () -> task.run(context(task)));
        assertTrue(error.getMessage().contains("no longer approved"));
        verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void shouldHandleEarlyKillAndPermitLaterReuse(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withBody(imported("inserted", ""))));
        var task = task(server).build();
        task.kill();
        assertThrows(java.util.concurrent.CancellationException.class, () -> task.run(context(task)));
        verify(0, anyRequestedFor(anyUrl()));
        assertEquals(1, task.run(context(task)).getSubmittedRows());
    }

    @Test
    void shouldKeepTheAcknowledgedRowAndStopRemainingWritesOnKill(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/api/now/import/imp_computer")).willReturn(created().withFixedDelay(500).withBody(imported("inserted", ""))));
        var task = task(server).from(List.of(Map.of("name", "one"), Map.of("name", "two"))).build();
        var context = context(task);
        var result = CompletableFuture.supplyAsync(() ->
        {
            try {
                return task.run(context);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(1, postRequestedFor(urlEqualTo("/api/now/import/imp_computer"))));
        task.kill();
        var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        var message = error.getCause().getMessage();
        assertTrue(message.contains("cancelled"));
        verify(1, postRequestedFor(urlEqualTo("/api/now/import/imp_computer")));
    }

    private URI partialUri(String message) {
        var match = java.util.regex.Pattern.compile("Partial results: (kestra://\\S+)\\. ").matcher(message);
        assertTrue(match.find(), message);
        return URI.create(match.group(1));
    }
}
