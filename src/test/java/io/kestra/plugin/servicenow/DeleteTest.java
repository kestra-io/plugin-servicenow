package io.kestra.plugin.servicenow;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@KestraTest
@WireMockTest(httpPort = 8082)
public class DeleteTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldDeleteWhenGivenValidInput(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        final String tableName = "fakeTableName";
        final String sysId = "04ce72c9c0a8016600b5b7f75ac67b5b";

        stubFor(delete(urlEqualTo("/service-now.com/api/now/table/" + tableName + "/" + sysId))
            .willReturn(aResponse().withStatus(204)));
        stubFor(any(urlEqualTo("/service-now.com/oauth_token.do")).willReturn(okJson("{\"access_token\":\"token\"}")));

        RunContext runContext = runContextFactory.of(Map.of());

        Delete task = Delete.builder()
            .table(Property.of(tableName))
            .sysId(Property.of(sysId))
            .clientId(Property.of("clientId"))
            .clientSecret(Property.of("clientSecret"))
            .username(Property.of("username"))
            .password(Property.of("password"))
            .domain(Property.of("kestra"))
            .uri(wireMockRuntimeInfo.getHttpBaseUrl() + "/service-now.com/")
            .build();

        var output = task.run(runContext);

        assertThat(output.isDeleted(), is(true));
    }
}
