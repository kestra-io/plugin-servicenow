package io.kestra.plugin.servicenow;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.*;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.netty.NettyHttpClientFactory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

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

    @Getter(AccessLevel.NONE)
    private transient String token;

    private static final NettyHttpClientFactory FACTORY = new NettyHttpClientFactory();

    protected HttpClient client(String base) throws IllegalVariableEvaluationException, MalformedURLException, URISyntaxException {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setConnectTimeout(Duration.ofSeconds(60));
        configuration.setReadTimeout(Duration.ofSeconds(60));
        configuration.setReadIdleTimeout(Duration.ofSeconds(60));

        return FACTORY.createClient(URI.create(base).toURL(), configuration);
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

        try (HttpClient client = this.client(this.baseUri(runContext))) {
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

            try (HttpClient client = this.client(this.baseUri(runContext))) {
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
