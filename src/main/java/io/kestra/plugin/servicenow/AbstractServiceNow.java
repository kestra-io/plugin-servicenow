package io.kestra.plugin.servicenow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BasicAuthConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

import jakarta.validation.constraints.NotNull;


@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractServiceNow extends Task {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

    @NotNull
    @Schema(
        title = "ServiceNow domain",
        description = "Subdomain used to build `https://<domain>.service-now.com/`; do not include protocol"
    )
    private Property<String> domain;

    @NotNull
    @Schema(title = "ServiceNow username", description = "Used with 'password' for Basic Auth or with client credentials for the OAuth password grant")
    private Property<String> username;

    @NotNull
    @Schema(title = "ServiceNow password", description = "Account password used with 'username' for Basic Auth or OAuth password grant")
    private Property<String> password;

    @Schema(title = "ServiceNow OAuth client ID", description = "Required with 'clientSecret' plus 'username' and 'password' to switch requests to OAuth bearer tokens")
    private Property<String> clientId;

    @Schema(title = "ServiceNow OAuth client secret", description = "Paired with 'clientId' when using the OAuth password grant")
    private Property<String> clientSecret;

    @Schema(title = "Additional request headers", description = "Optional key/value headers rendered per execution and sent with every call")
    protected Property<Map<CharSequence, CharSequence>> headers;

    @Schema(title = "HTTP client configuration", description = "Advanced HTTP settings such as timeouts, proxies, and TLS; defaults to Kestra HTTP client values")
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

        URI uri = URI.create(baseUri(runContext) + "oauth_token.do");

        Map<String, Object> requestBody = Map.of(
            "grant_type", "password",
            "client_id", runContext.render(this.clientId).as(String.class).orElseThrow(),
            "client_secret", runContext.render(this.clientSecret).as(String.class).orElseThrow(),
            "username", runContext.render(this.username).as(String.class).orElseThrow(),
            "password", runContext.render(this.password).as(String.class).orElseThrow()
        );

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(uri)
            .method("POST")
            .body(HttpRequest.UrlEncodedRequestBody.builder().content(requestBody).build());

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

        requestBuilder
            .addHeader("Content-Type", "application/json")
            .build();

        if (this.clientId != null) {
            requestBuilder.addHeader("Authorization", "Bearer " + this.token(runContext));
        } else {
            var optionsBuilder = options != null ? options.toBuilder() : HttpConfiguration.builder();
            options = optionsBuilder.auth(
                BasicAuthConfiguration.builder()
                    .username(this.username)
                    .password(this.password).build()
            ).build();
        }

        var request = requestBuilder.build();
        try (HttpClient client = new HttpClient(runContext, options)) {
            HttpResponse<String> response = client.request(request, String.class);
            RES parsedResponse = null;
            if (responseType != Void.class && response.getBody() != null && !response.getBody().isEmpty()) {
                parsedResponse = MAPPER.readValue(response.getBody(), responseType);
            }

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
