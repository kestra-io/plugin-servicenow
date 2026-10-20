package io.kestra.plugin.servicenow;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@WireMockTest
class AuthenticationTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void fetchesANewTokenWhenTheSameTaskRunsAgain(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            post(urlEqualTo("/oauth_token.do"))
                .inScenario("token expiry").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"access_token\":\"first-token\"}"))
                .willSetStateTo("first token issued")
        );
        stubFor(
            get(urlPathEqualTo("/api/now/table/incident"))
                .inScenario("token expiry").whenScenarioStateIs("first token issued")
                .withHeader("Authorization", equalTo("Bearer first-token"))
                .willReturn(okJson("{\"result\":[{\"number\":\"INC001\"}]}"))
                .willSetStateTo("first token expired")
        );
        stubFor(
            post(urlEqualTo("/oauth_token.do"))
                .inScenario("token expiry").whenScenarioStateIs("first token expired")
                .willReturn(okJson("{\"access_token\":\"second-token\"}"))
                .willSetStateTo("second token issued")
        );
        stubFor(
            get(urlPathEqualTo("/api/now/table/incident"))
                .inScenario("token expiry").whenScenarioStateIs("second token issued")
                .withHeader("Authorization", equalTo("Bearer second-token"))
                .willReturn(okJson("{\"result\":[{\"number\":\"INC002\"}]}"))
        );

        var task = oauthTask(server);
        var context = context("alice");

        assertThat(task.run(context).getResults().getFirst().get("number"), is("INC001"));
        assertThat(task.run(context).getResults().getFirst().get("number"), is("INC002"));
        verify(2, postRequestedFor(urlEqualTo("/oauth_token.do")));
    }

    @Test
    void rendersOAuthCredentialsForEachExecution(WireMockRuntimeInfo server) throws Exception {
        stubOAuthUser("alice", 0);
        stubOAuthUser("bob", 0);
        var task = oauthTask(server);

        assertThat(task.run(context("alice")).getResults().getFirst().get("owner"), is("alice"));
        assertThat(task.run(context("bob")).getResults().getFirst().get("owner"), is("bob"));
        verify(2, postRequestedFor(urlEqualTo("/oauth_token.do")));
    }

    @Test
    void overlappingExecutionsUseTheirOwnOAuthCredentials(WireMockRuntimeInfo server) throws Exception {
        stubOAuthUser("alice", 2000);
        stubOAuthUser("bob", 0);
        var task = oauthTask(server);

        var first = CompletableFuture.supplyAsync(() ->
        {
            try {
                return task.run(context("alice"));
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> verify(
                    getRequestedFor(urlPathEqualTo("/api/now/table/incident"))
                        .withHeader("Authorization", equalTo("Bearer alice-token"))
                )
            );
            assertThat(task.run(context("bob")).getResults().getFirst().get("owner"), is("bob"));
            assertThat(first.get(10, TimeUnit.SECONDS).getResults().getFirst().get("owner"), is("alice"));
            verify(2, postRequestedFor(urlEqualTo("/oauth_token.do")));
        } finally {
            first.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void basicAuthDoesNotSetOptionsOnTheTask(WireMockRuntimeInfo server) throws Exception {
        verifyBasicAuthOptions(server, null);
    }

    @Test
    void basicAuthPreservesConfiguredOptions(WireMockRuntimeInfo server) throws Exception {
        var options = HttpConfiguration.builder().readTimeout(Duration.ofSeconds(5)).build();
        verifyBasicAuthOptions(server, options);
    }

    private void verifyBasicAuthOptions(WireMockRuntimeInfo server, HttpConfiguration options) throws Exception {
        var task = Get.builder()
            .uri(Property.ofValue(server.getHttpBaseUrl()))
            .table(Property.ofValue("incident"))
            .username(Property.ofExpression("{{ user }}"))
            .password(Property.ofExpression("{{ password }}"))
            .options(options)
            .build();

        for (var user : new String[] { "alice", "bob" }) {
            stubFor(
                get(urlPathEqualTo("/api/now/table/incident"))
                    .withBasicAuth(user, user + "-password")
                    .willReturn(okJson("{\"result\":[{\"owner\":\"" + user + "\"}]}"))
            );
            assertThat(task.run(context(user)).getResults().getFirst().get("owner"), is(user));
            assertThat(task.getOptions(), sameInstance(options));
        }
        verify(0, postRequestedFor(urlEqualTo("/oauth_token.do")));
    }

    private Get oauthTask(WireMockRuntimeInfo server) {
        return Get.builder()
            .uri(Property.ofValue(server.getHttpBaseUrl()))
            .table(Property.ofValue("incident"))
            .clientId(Property.ofExpression("{{ client_id }}"))
            .clientSecret(Property.ofExpression("{{ client_secret }}"))
            .username(Property.ofExpression("{{ user }}"))
            .password(Property.ofExpression("{{ password }}"))
            .build();
    }

    private RunContext context(String user) {
        return runContextFactory.of(
            Map.of(
                "user", user,
                "password", user + "-password",
                "client_id", user + "-client",
                "client_secret", user + "-secret"
            )
        );
    }

    private void stubOAuthUser(String user, int responseDelay) {
        stubFor(
            post(urlEqualTo("/oauth_token.do"))
                .withRequestBody(containing("username=" + user))
                .withRequestBody(containing("password=" + user + "-password"))
                .withRequestBody(containing("client_id=" + user + "-client"))
                .withRequestBody(containing("client_secret=" + user + "-secret"))
                .willReturn(okJson("{\"access_token\":\"" + user + "-token\"}"))
        );
        stubFor(
            get(urlPathEqualTo("/api/now/table/incident"))
                .withHeader("Authorization", equalTo("Bearer " + user + "-token"))
                .willReturn(okJson("{\"result\":[{\"owner\":\"" + user + "\"}]}").withFixedDelay(responseDelay))
        );
    }
}
