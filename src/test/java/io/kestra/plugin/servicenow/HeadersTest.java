package io.kestra.plugin.servicenow;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@WireMockTest
class HeadersTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void sendsRenderedHeadersOnTableRequestWithBasicAuth(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlPathEqualTo("/api/now/table/incident")).willReturn(okJson("{\"result\":[]}")));

        Get.builder()
            .uri(Property.ofValue(server.getHttpBaseUrl()))
            .table(Property.ofValue("incident"))
            .username(Property.ofValue("alice"))
            .password(Property.ofValue("alice-password"))
            .headers(Property.ofExpression("{\"X-Correlation-Id\": \"{{ correlation_id }}\"}"))
            .build()
            .run(context());

        verify(
            getRequestedFor(urlPathEqualTo("/api/now/table/incident"))
                .withHeader("X-Correlation-Id", equalTo("run-42"))
                .withBasicAuth(new BasicCredentials("alice", "alice-password"))
        );
    }

    @Test
    void sendsRenderedHeadersOnTokenAndTableRequestsWithOAuth(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/oauth_token.do")).willReturn(okJson("{\"access_token\":\"token\"}")));
        stubFor(post(urlPathEqualTo("/api/now/table/incident")).willReturn(okJson("{\"result\":{\"sys_id\":\"abc\"}}")));

        Post.builder()
            .uri(Property.ofValue(server.getHttpBaseUrl()))
            .table(Property.ofValue("incident"))
            .username(Property.ofValue("alice"))
            .password(Property.ofValue("alice-password"))
            .clientId(Property.ofValue("client"))
            .clientSecret(Property.ofValue("secret"))
            .headers(Property.ofExpression("{\"X-Correlation-Id\": \"{{ correlation_id }}\"}"))
            .data(Property.ofValue(Map.of("short_description", "test")))
            .build()
            .run(context());

        verify(postRequestedFor(urlEqualTo("/oauth_token.do")).withHeader("X-Correlation-Id", equalTo("run-42")));
        verify(
            postRequestedFor(urlPathEqualTo("/api/now/table/incident"))
                .withHeader("X-Correlation-Id", equalTo("run-42"))
                .withHeader("Authorization", equalTo("Bearer token"))
        );
    }

    @Test
    void userContentTypeReplacesTheDefault(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlPathEqualTo("/api/now/table/incident")).willReturn(okJson("{\"result\":[]}")));

        Get.builder()
            .uri(Property.ofValue(server.getHttpBaseUrl()))
            .table(Property.ofValue("incident"))
            .username(Property.ofValue("alice"))
            .password(Property.ofValue("alice-password"))
            .headers(Property.ofValue(Map.of("content-type", "application/json; charset=UTF-8")))
            .build()
            .run(context());

        var requests = findAll(getRequestedFor(urlPathEqualTo("/api/now/table/incident")));
        assertThat(requests, hasSize(1));
        assertThat(requests.getFirst().getHeaders().getHeader("Content-Type").values(), contains("application/json; charset=UTF-8"));
    }

    @Test
    void updateReturnsNullResultWhenServiceNowSendsNoBody(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            put(urlEqualTo("/api/now/table/incident/abc"))
                .withHeader("X-no-response-body", equalTo("true"))
                .willReturn(aResponse().withStatus(200))
        );

        var output = Update.builder()
            .uri(Property.ofValue(server.getHttpBaseUrl()))
            .table(Property.ofValue("incident"))
            .sysId(Property.ofValue("abc"))
            .username(Property.ofValue("alice"))
            .password(Property.ofValue("alice-password"))
            .headers(Property.ofValue(Map.of("X-no-response-body", "true")))
            .data(Property.ofValue(Map.of("state", "2")))
            .build()
            .run(context());

        assertThat(output.getResult(), nullValue());
    }

    private RunContext context() {
        return runContextFactory.of(Map.of("correlation_id", "run-42"));
    }
}
