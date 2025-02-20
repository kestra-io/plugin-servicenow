package io.kestra.plugin.servicenow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.utils.Rethrow;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.validation.constraints.NotNull;

import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractServiceNow extends Task  {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

    @NotNull
    @Schema(
        title = "ServiceNow domain.",
        description = "Will be used to generate the URL: `https://[[DOMAIN]].service-now.com/`"
    )
    private Property<String> domain;

    @NotNull
    @Schema(title = "ServiceNow username.")
    private Property<String> username;

    @NotNull
    @Schema(title = "ServiceNow password.")
    private Property<String> password;

    @NotNull
    @Schema(title = "ServiceNow client ID.")
    private Property<String> clientId;

    @NotNull
    @Schema(title = "ServiceNow client secret.")
    private Property<String> clientSecret;

    @Schema(title = "The headers to pass to the request")
    protected Property<Map<CharSequence, CharSequence>> headers;

    @Schema(title = "The HTTP client configuration.")
    protected HttpConfiguration options;

    @Getter(AccessLevel.NONE)
    private transient String token;

    @Getter(AccessLevel.NONE)
    private transient String uri;

    protected String baseUri(RunContext runContext) throws IllegalVariableEvaluationException {
        if (this.uri != null) {
            return this.uri;
        }
        return "https://" + runContext.render(this.domain).as(String.class).orElseThrow() + ".service-now.com/";
    }

    private String token(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        if (this.token != null) {
            return this.token;
        }

        URI uri = URI.create(baseUri(runContext) + "/oauth_token.do");

        Map<String, String> requestBody = Map.of(
            "grant_type", "password",
            "client_id", runContext.render(this.clientId).as(String.class).orElseThrow(),
            "client_secret", runContext.render(this.clientSecret).as(String.class).orElseThrow(),
            "username", runContext.render(this.username).as(String.class).orElseThrow(),
            "password", URLEncoder.encode(runContext.render(this.password).as(String.class).orElseThrow(), StandardCharsets.UTF_8)
        );

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(uri)
            .method("POST")
            .body(HttpRequest.JsonRequestBody.builder().content(requestBody).build())
            .addHeader("Content-Type", "application/x-www-form-urlencoded");

        if (this.headers != null) {
            runContext.render(this.headers)
                .asMap(CharSequence.class, CharSequence.class)
                .forEach((key, value) -> {
                    try {
                        requestBuilder.addHeader(
                            key.toString(),
                            runContext.render(value.toString())
                        );
                    } catch (IllegalVariableEvaluationException ex) {
                        throw new RuntimeException("Failed to render header value", ex);
                    }
                });
        }

        try (HttpClient client = new HttpClient(runContext, options)) {
            HttpResponse<Map<String, String>> exchange = client.request(requestBuilder.build());

            Map<String, String> tokenResponse = exchange.getBody();
            if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
                throw new IllegalStateException("Invalid token request with response " + tokenResponse);
            }
            this.token = tokenResponse.get("access_token");
            return this.token;
        } catch (IOException e) {
            throw new RuntimeException("Error fetching access token", e);
        }
    }


    protected <RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, Class<RES> responseType)
        throws HttpClientException, IllegalVariableEvaluationException {

        var request = requestBuilder
            .addHeader("Authorization", "Bearer " + this.token(runContext))
            .addHeader("Content-Type", "application/json")
            .build();

        try (HttpClient client = new HttpClient(runContext, options)) {
            HttpResponse<String> response = client.request(request, String.class);
            RES parsedResponse = MAPPER.readValue(response.getBody(), responseType);
            return HttpResponse.<RES>builder()
                .request(request)
                .body(parsedResponse)
                .headers(response.getHeaders())
                .status(response.getStatus())
                .build();
        } catch (HttpClientResponseException e) {
            throw new HttpClientResponseException(
                "Request failed '" + Objects.requireNonNull(e.getResponse()).getStatus().getCode() +
                    "' and body '" + e.getResponse().getBody() + "'",
                e.getResponse()
            );
        } catch (IOException e) {
            throw new RuntimeException("Error parsing response body", e);
        }
        }

}
