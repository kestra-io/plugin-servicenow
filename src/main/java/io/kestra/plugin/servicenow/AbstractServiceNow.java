package io.kestra.plugin.servicenow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BasicAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractServiceNow extends Task {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson().copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Pattern SECRET_HEADER = Pattern.compile(".*(authorization|cookie|token|api.key).*", Pattern.CASE_INSENSITIVE);

    @Schema(
        title = "ServiceNow domain",
        description = "Subdomain used to build `https://<domain>.service-now.com/`; do not include protocol. Required unless `uri` is set."
    )
    @PluginProperty(group = "connection")
    private Property<String> domain;

    @NotNull
    @Schema(title = "ServiceNow username", description = "Used with 'password' for Basic Auth or with client credentials for the OAuth password grant")
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> username;

    @NotNull
    @Schema(title = "ServiceNow password", description = "Account password used with 'username' for Basic Auth or OAuth password grant")
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> password;

    @Schema(title = "ServiceNow OAuth client ID", description = "Required with 'clientSecret' plus 'username' and 'password' to switch requests to OAuth bearer tokens")
    @PluginProperty(group = "connection")
    @ToString.Exclude
    private Property<String> clientId;

    @Schema(title = "ServiceNow OAuth client secret", description = "Paired with 'clientId' when using the OAuth password grant")
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> clientSecret;

    @Schema(title = "Additional request headers", description = "Optional key/value headers rendered per execution and sent with every call")
    @PluginProperty(group = "advanced")
    @ToString.Exclude
    protected Property<Map<CharSequence, CharSequence>> headers;

    @Schema(title = "HTTP client configuration", description = "Advanced HTTP settings such as timeouts, proxies, and TLS; defaults to Kestra HTTP client values")
    @PluginProperty(group = "advanced")
    @ToString.Exclude
    protected HttpConfiguration options;

    @Schema(
        title = "ServiceNow base URI",
        description = "Optional base URL override for custom or mock ServiceNow instances; defaults to `https://<domain>.service-now.com/` when not set. Takes precedence over `domain`."
    )
    @PluginProperty(group = "connection")
    private Property<String> uri;

    protected CreatedRecord createRecord(RunContext runContext, String table, Map<String, Object> data) throws Exception {
        String baseUri = baseUri(runContext);

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(URI.create(baseUri + "api/now/table/" + table))
            .method("POST")
            .body(HttpRequest.JsonRequestBody.builder().content(data).build());

        HttpResponse<RecordResponse> response = this.request(runContext, requestBuilder, RecordResponse.class);

        if (response.getBody() == null) {
            throw new IllegalStateException("The ServiceNow response has an empty body (status %d).".formatted(response.getStatus().getCode()));
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
    @JsonIgnoreProperties(ignoreUnknown = true)
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

    private String token(RunContext runContext, HttpConfiguration configuration) throws IllegalVariableEvaluationException, HttpClientException {
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

        addHeaders(runContext, requestBuilder);

        try (HttpClient client = new HttpClient(runContext, configuration)) {
            var token = new AtomicReference<String>();
            client.request(requestBuilder.build(), response ->
            {
                try (var input = response.getBody()) {
                    var bytes = input == null ? new byte[0] : input.readNBytes(ServiceNowApi.MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > ServiceNowApi.MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("ServiceNow OAuth response exceeds 4 MiB. Check the token endpoint.");
                    }
                    var document = bytes.length == 0 ? null : JacksonMapper.ofJson().readTree(bytes);
                    if (
                        response.getStatus().getCode() < 200 || response.getStatus().getCode() >= 300 || document == null
                            || !document.path("access_token").isTextual() || document.path("access_token").asText().isBlank()
                    ) {
                        throw new IllegalStateException("ServiceNow did not return a valid OAuth token. Check the client credentials and account permissions.");
                    }
                    token.set(document.path("access_token").asText());
                } catch (IOException e) {
                    throw new IllegalStateException("ServiceNow OAuth response could not be decoded. Check the token endpoint.");
                }
            });
            if (token.get() == null) {
                throw new IllegalStateException("ServiceNow returned no OAuth token. Check the client credentials and account permissions.");
            }
            return token.get();
        } catch (IOException e) {
            throw new RuntimeException("Error fetching access token", e);
        }
    }

    private Map<String, String> addHeaders(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder) throws IllegalVariableEvaluationException {
        Map<String, String> rHeaders = new LinkedHashMap<>();
        runContext.render(this.headers).asMap(CharSequence.class, CharSequence.class)
            .forEach((key, value) -> rHeaders.put(key.toString(), value.toString()));
        rHeaders.forEach(requestBuilder::addHeader);
        return rHeaders;
    }

    protected <RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, Class<RES> responseType)
        throws HttpClientException, IllegalVariableEvaluationException {
        var configured = configure(runContext, requestBuilder, options);
        var request = configured.request();
        try (HttpClient client = new HttpClient(runContext, configured.options())) {
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
            var response = Objects.requireNonNull(e.getResponse());
            Object body = response.getBody();
            String bodyAsString;
            if (body == null) {
                bodyAsString = "";
            } else if (body instanceof byte[] bytes) {
                bodyAsString = new String(bytes, StandardCharsets.UTF_8);
            } else {
                bodyAsString = body.toString();
            }
            throw new HttpClientResponseException(
                "Request failed '" + response.getStatus().getCode() +
                    "' and body '" + bodyAsString + "'",
                response
            );
        } catch (IOException e) {
            throw new RuntimeException("Error parsing response body", e);
        }
    }

    protected ServiceNowApi api(RunContext context, AbstractServiceNowPollingTask.Poll poll) throws Exception {
        if (poll != null) {
            poll.check();
        }
        var accessToken = new AtomicReference<String>();
        if (clientId != null) {
            accessToken.set(token(context, nativeOptions(options, poll)));
        }
        if (poll != null) {
            poll.check();
        }
        return new ServiceNowApi(baseUri(context), builder ->
        {
            var original = builder.build();
            var configured = configure(context, builder, nativeOptions(options, poll), accessToken.get());
            var response = nativeRequest(context, configured, poll);
            if (response.status() == 401 && clientId != null) {
                accessToken.set(token(context, nativeOptions(options, poll)));
                var retry = HttpRequest.builder().uri(original.getUri()).method(original.getMethod()).body(original.getBody()).headers(original.getHeaders());
                var refreshed = configure(context, retry, nativeOptions(options, poll), accessToken.get());
                var secrets = new HashSet<>(configured.secrets());
                secrets.addAll(refreshed.secrets());
                response = nativeRequest(context, new ConfiguredRequest(refreshed.request(), refreshed.options(), Set.copyOf(secrets)), poll);
            }
            return response;
        });
    }

    private ServiceNowApi.Response nativeRequest(RunContext context, ConfiguredRequest configured, AbstractServiceNowPollingTask.Poll poll) throws Exception {
        if (poll != null) {
            poll.check();
        }
        var result = new AtomicReference<ServiceNowApi.Response>();
        try (var client = new HttpClient(context, nativeOptions(configured.options(), poll))) {
            client.request(configured.request(), response ->
            {
                try (var input = response.getBody()) {
                    var body = input == null ? new byte[0] : input.readNBytes(ServiceNowApi.MAX_RESPONSE_BYTES + 1);
                    if (body.length > ServiceNowApi.MAX_RESPONSE_BYTES) {
                        throw new ServiceNowApi.ApiException(response.getStatus().getCode(), "ServiceNow response exceeds 4 MiB. Narrow the query or split the load.");
                    }
                    result.set(new ServiceNowApi.Response(response.getStatus().getCode(), body, configured.secrets()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (Exception e) {
            if (poll != null) {
                poll.check();
            }
            throw e;
        }
        if (poll != null && "GET".equals(configured.request().getMethod())) {
            poll.check();
        }
        if (result.get() == null) {
            throw new IllegalStateException("ServiceNow returned no response. Check the instance URL and network.");
        }
        return result.get();
    }

    private HttpConfiguration nativeOptions(HttpConfiguration options, AbstractServiceNowPollingTask.Poll poll) {
        var remaining = poll == null ? Duration.ofSeconds(15) : poll.remaining();
        var readTimeout = remaining.compareTo(Duration.ofSeconds(15)) < 0 ? remaining : Duration.ofSeconds(15);
        var connectTimeout = remaining.compareTo(Duration.ofSeconds(5)) < 0 ? remaining : Duration.ofSeconds(5);
        var builder = options == null ? HttpConfiguration.builder() : options.toBuilder();
        return builder.timeout(TimeoutConfiguration.builder().connectTimeout(Property.ofValue(connectTimeout)).readIdleTimeout(Property.ofValue(readTimeout)).build())
            .auth(options == null ? null : options.getAuth())
            .allowFailed(Property.ofValue(true)).allowedResponseCodes(null).followRedirects(Property.ofValue(false))
            .logs(null).retry(Exponential.builder().interval(Duration.ofSeconds(1)).maxInterval(Duration.ofSeconds(30)).maxAttempts(1).build()).build();
    }

    private ConfiguredRequest configure(RunContext context, HttpRequest.HttpRequestBuilder builder, HttpConfiguration configuration)
        throws IllegalVariableEvaluationException, HttpClientException {
        return configure(context, builder, configuration, clientId == null ? null : token(context, configuration));
    }

    private ConfiguredRequest configure(RunContext context, HttpRequest.HttpRequestBuilder builder, HttpConfiguration configuration, String accessToken)
        throws IllegalVariableEvaluationException {
        var rHeaders = addHeaders(context, builder);
        if (rHeaders.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
            builder.addHeader("Content-Type", "application/json");
        }
        var secrets = new HashSet<String>();
        for (var property : Arrays.asList(username, password, clientId, clientSecret)) {
            context.render(property).as(String.class).filter(value -> !value.isBlank()).ifPresent(secrets::add);
        }
        rHeaders.forEach((key, value) ->
        {
            if (SECRET_HEADER.matcher(key).matches()) {
                secrets.add(value);
            }
        });
        var configuredOptions = configuration;
        if (accessToken != null) {
            secrets.add(accessToken);
            builder.addHeader("Authorization", "Bearer " + accessToken);
        } else {
            var optionsBuilder = configuration == null ? HttpConfiguration.builder() : configuration.toBuilder();
            configuredOptions = optionsBuilder.auth(BasicAuthConfiguration.builder().username(username).password(password).build()).build();
        }
        return new ConfiguredRequest(builder.build(), configuredOptions, Set.copyOf(secrets));
    }

    private record ConfiguredRequest(HttpRequest request, HttpConfiguration options, Set<String> secrets) {
    }

}
