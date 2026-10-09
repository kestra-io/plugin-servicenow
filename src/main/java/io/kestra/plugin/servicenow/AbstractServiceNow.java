package io.kestra.plugin.servicenow;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BasicAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractServiceNow extends Task {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

    @Schema(
        title = "ServiceNow domain",
        description = "Subdomain used to build `https://<domain>.service-now.com/`; do not include protocol. Required unless `uri` is set."
    )
    @PluginProperty(group = "connection")
    private Property<String> domain;

    @NotNull
    @Schema(title = "ServiceNow username", description = "Used with 'password' for Basic Auth or with client credentials for the OAuth password grant")
    @PluginProperty(group = "connection", secret = true)
    private Property<String> username;

    @NotNull
    @Schema(title = "ServiceNow password", description = "Account password used with 'username' for Basic Auth or OAuth password grant")
    @PluginProperty(group = "connection", secret = true)
    private Property<String> password;

    @Schema(title = "ServiceNow OAuth client ID", description = "Required with 'clientSecret' plus 'username' and 'password' to switch requests to OAuth bearer tokens")
    @PluginProperty(group = "connection")
    private Property<String> clientId;

    @Schema(title = "ServiceNow OAuth client secret", description = "Paired with 'clientId' when using the OAuth password grant")
    @PluginProperty(group = "connection", secret = true)
    private Property<String> clientSecret;

    @Schema(title = "Additional request headers", description = "Optional key/value headers rendered per execution and sent with every call")
    @PluginProperty(group = "advanced")
    protected Property<Map<CharSequence, CharSequence>> headers;

    @Schema(title = "HTTP client configuration", description = "Advanced HTTP settings such as timeouts, proxies, and TLS; defaults to Kestra HTTP client values")
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    @Schema(
        title = "ServiceNow base URI",
        description = "Optional base URL override for custom or mock ServiceNow instances; defaults to `https://<domain>.service-now.com/` when not set. Takes precedence over `domain`."
    )
    @PluginProperty(group = "connection")
    private Property<String> uri;

    @Getter(AccessLevel.NONE)
    private transient String token;

    protected CreatedRecord createRecord(RunContext runContext, String table, Map<String, Object> data) throws Exception {
        String baseUri = baseUri(runContext);

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(URI.create(baseUri + "api/now/table/" + table))
            .method("POST")
            .body(HttpRequest.JsonRequestBody.builder().content(data).build());

        HttpResponse<RecordResponse> response = this.request(runContext, requestBuilder, RecordResponse.class);

        if (response.getBody() == null) {
            throw new IllegalStateException("Empty body on '" + response + "'");
        }

        runContext.logger().info("Created a record in '{}': '{}'", table, response.getBody());

        Map<String, Object> result = response.getBody().getResult();
        String sysId = stringValue(result, "sys_id");

        return CreatedRecord.builder()
            .result(result)
            .number(stringValue(result, "number"))
            .sysId(sysId)
            .url(sysId == null ? null : baseUri + table + ".do?sys_id=" + sysId)
            .build();
    }

    @Data
    @NoArgsConstructor
    public static class RecordResponse {
        Map<String, Object> result;
    }

    private static String stringValue(Map<String, Object> result, String field) {
        Object value = result == null ? null : result.get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    protected String baseUri(RunContext runContext) throws IllegalVariableEvaluationException {
        var rUri = runContext.render(this.uri).as(String.class).orElse(null);
        if (rUri != null) {
            return rUri.endsWith("/") ? rUri : rUri + "/";
        }
        var rDomain = runContext.render(this.domain).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Either 'domain' or 'uri' must be provided."));
        return "https://" + rDomain + ".service-now.com/";
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
                .forEach((key, value) ->
                {
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

            return HttpResponse.<RES> builder()
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
