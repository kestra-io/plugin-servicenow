package io.kestra.plugin.servicenow;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.docs.JsonSchemaGenerator;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
@WireMockTest(httpPort = 8083)
class CreateIncidentTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private JsonSchemaGenerator jsonSchemaGenerator;

    @Test
    void shouldPostTheTypedFieldsToTheIncidentTable(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(any(urlPathEqualTo("/service-now.com/api/now/table/incident")).willReturn(okJson(PostTests.DATA)));
        stubFor(any(urlPathEqualTo("/service-now.com/oauth_token.do")).willReturn(okJson("{\"access_token\":\"token\"}")));

        CreateIncident task = CreateIncident.builder()
            .shortDescription(Property.ofValue("Payment API is failing"))
            .incidentDescription(Property.ofValue("Checkout returns a 502"))
            .urgency(Property.ofValue("1"))
            .impact(Property.ofValue("2"))
            .data(Property.ofValue(Map.of("caller_id", "abc", "urgency", "3")))
            .clientId(Property.ofValue("clientId"))
            .clientSecret(Property.ofValue("clientSecret"))
            .username(Property.ofValue("username"))
            .password(Property.ofValue("password"))
            .domain(Property.ofValue("kestra"))
            .uri(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl() + "/service-now.com/"))
            .build();

        var output = task.run(runContextFactory.of());

        verify(postRequestedFor(urlPathEqualTo("/service-now.com/api/now/table/incident"))
            .withRequestBody(matchingJsonPath("$.short_description", equalTo("Payment API is failing")))
            .withRequestBody(matchingJsonPath("$.description", equalTo("Checkout returns a 502")))
            .withRequestBody(matchingJsonPath("$.urgency", equalTo("1")))
            .withRequestBody(matchingJsonPath("$.impact", equalTo("2")))
            .withRequestBody(matchingJsonPath("$.caller_id", equalTo("abc"))));
        assertThat(output.getNumber(), is("INC0010002"));
        assertThat(output.getUrl(), is(wmRuntimeInfo.getHttpBaseUrl() + "/service-now.com/incident.do?sys_id=c537bae64f411200adf9f8e18110c76e"));
    }

    @Test
    void shouldKeepTheDataDescriptionWhenTheTypedOneIsBlank(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(any(urlPathEqualTo("/service-now.com/api/now/table/incident")).willReturn(okJson(PostTests.DATA)));
        stubFor(any(urlPathEqualTo("/service-now.com/oauth_token.do")).willReturn(okJson("{\"access_token\":\"token\"}")));

        CreateIncident task = CreateIncident.builder()
            .shortDescription(Property.ofValue("Payment API is failing"))
            .incidentDescription(Property.ofValue(""))
            .data(Property.ofValue(Map.of("description", "from data")))
            .clientId(Property.ofValue("clientId"))
            .clientSecret(Property.ofValue("clientSecret"))
            .username(Property.ofValue("username"))
            .password(Property.ofValue("password"))
            .domain(Property.ofValue("kestra"))
            .uri(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl() + "/service-now.com/"))
            .build();

        task.run(runContextFactory.of());

        verify(postRequestedFor(urlPathEqualTo("/service-now.com/api/now/table/incident"))
            .withRequestBody(matchingJsonPath("$.description", equalTo("from data"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDescribeHowCasesMapToTheIncident() {
        var inputs = (Map<String, Map<String, Object>>) jsonSchemaGenerator.properties(Task.class, CreateIncident.class).get("properties");

        assertThat(inputs.get("shortDescription").get("$ticketingRole"), is("CASE_TITLE"));
        assertThat(inputs.get("incidentDescription").get("$ticketingRole"), is("CASE_DESCRIPTION"));
        assertThat(inputs.get("urgency").get("$ticketingRole"), is("CASE_SEVERITY"));
        assertThat(inputs.get("urgency").get("$ticketingValueMap"), is(Map.of("CRITICAL", "1", "HIGH", "1", "MEDIUM", "2", "LOW", "3")));
        assertThat(inputs.get("impact").get("$ticketingValueMap"), is(Map.of("CRITICAL", "1", "HIGH", "2", "MEDIUM", "2", "LOW", "3")));
    }
}
