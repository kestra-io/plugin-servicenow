package io.kestra.plugin.servicenow;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.http.client.netty.NettyHttpClientFactory;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractServiceNow extends Task  {
    @NotNull
    @NotEmpty
    @Schema(
        title = "ServiceNow domain.",
        description = "Will be used to generate the url: `https://[[DOMAIN]].service-now.com/`"
    )
    @PluginProperty(dynamic = true)
    private String domain;

    @NotNull
    @NotEmpty
    @Schema(
        title = "ServiceNow username."
    )
    @PluginProperty(dynamic = true)
    private String username;

    @NotNull
    @NotEmpty
    @Schema(
        title = "ServiceNow password."
    )
    @PluginProperty(dynamic = true)
    private String password;

    @NotNull
    @NotEmpty
    @Schema(
        title = "ServiceNow client ID."
    )
    @PluginProperty(dynamic = true)
    private String clientId;

    @NotNull
    @NotEmpty
    @Schema(
        title = "ServiceNow client secret."
    )
    @PluginProperty(dynamic = true)
    private String clientSecret;

    @Schema(
        title = "The headers to pass to the request"
    )
    @PluginProperty(dynamic = true)
    protected Map<CharSequence, CharSequence> headers;

    @Getter(AccessLevel.NONE)
    private transient String token;

    private static final NettyHttpClientFactory FACTORY = new NettyHttpClientFactory();

    protected HttpClient client(RunContext runContext, String base) throws IllegalVariableEvaluationException, MalformedURLException, URISyntaxException {
        MediaTypeCodecRegistry mediaTypeCodecRegistry = runContext.getApplicationContext().getBean(MediaTypeCodecRegistry.class);

        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setConnectTimeout(Duration.ofSeconds(60));
        configuration.setReadTimeout(Duration.ofSeconds(60));
        configuration.setReadIdleTimeout(Duration.ofSeconds(60));

        DefaultHttpClient client = (DefaultHttpClient) FACTORY.createClient(URI.create(base).toURL(), configuration);
        client.setMediaTypeCodecRegistry(mediaTypeCodecRegistry);

        return client;
    }

    private String baseUri(RunContext runContext) throws IllegalVariableEvaluationException {
        return "https://" + runContext.render(this.domain) + ".service-now.com/";
    }

    private String token(RunContext runContext) throws IllegalVariableEvaluationException, MalformedURLException, URISyntaxException {
        if (this.token != null) {
            return this.token;
        }

        MutableHttpRequest<String> request = HttpRequest.create(HttpMethod.POST, "/oauth_token.do")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=password" +
                "&client_id=" + runContext.render(this.clientId) +
                "&client_secret=" + runContext.render(this.clientSecret) +
                "&username=" + runContext.render(this.username) +
                "&password=" + URLEncoder.encode(runContext.render(this.password), StandardCharsets.UTF_8)
            );


        if (this.headers != null) {
            request.headers(this.headers
                .entrySet()
                .stream()
                .map(throwFunction(e -> new AbstractMap.SimpleEntry<>(
                    e.getKey(),
                    runContext.render(e.getValue().toString())
                )))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            );
        }

        try (HttpClient client = this.client(runContext, this.baseUri(runContext))) {
            HttpResponse<Map<String, String>> exchange = client.toBlocking().exchange(request, Argument.mapOf(String.class, String.class));

            Map<String, String> token = exchange.body();

            if (token == null || !token.containsKey("access_token")) {
                throw new IllegalStateException("Invalid token request with response " + token);
            }
            this.token = token.get("access_token");

            return this.token;
        }
    }

    protected <REQ, RES> HttpResponse<RES> request(RunContext runContext, MutableHttpRequest<REQ> request, Argument<RES> argument) throws HttpClientResponseException {
        try {
            request = request
                .bearerAuth(this.token(runContext))
                .contentType(MediaType.APPLICATION_JSON);

            try (HttpClient client = this.client(runContext, this.baseUri(runContext))) {
                return client.toBlocking().exchange(request, argument);
            }
        } catch (HttpClientResponseException e) {
            throw new HttpClientResponseException(
                "Request failed '" + e.getStatus().getCode() + "' and body '" + e.getResponse().getBody(String.class).orElse("null") + "'",
                e.getResponse()
            );
        } catch (IllegalVariableEvaluationException | MalformedURLException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
