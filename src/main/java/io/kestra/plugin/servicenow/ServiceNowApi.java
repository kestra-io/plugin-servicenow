package io.kestra.plugin.servicenow;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.serializers.JacksonMapper;

final class ServiceNowApi {
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    @FunctionalInterface
    interface Transport {
        Response execute(HttpRequest.HttpRequestBuilder request) throws Exception;
    }

    record Response(int status, byte[] body, Set<String> secrets) {
    }

    private final String base;
    private final Transport transport;

    ServiceNowApi(String base, Transport transport) {
        URI parsed;
        try {
            parsed = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ServiceNow uri must be a valid HTTP(S) base URL.");
        }
        if (
            parsed.getHost() == null || !("https".equals(parsed.getScheme()) || "http".equals(parsed.getScheme()))
                || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null
        ) {
            throw new IllegalArgumentException("ServiceNow uri must be an HTTP(S) base URL without credentials, query or fragment.");
        }
        this.base = base.endsWith("/") ? base : base + "/";
        this.transport = transport;
    }

    JsonNode get(String path, Map<String, ?> query) throws Exception {
        return call("GET", path, query, null);
    }

    JsonNode post(String path, Map<String, ?> query, Object body) throws Exception {
        return call("POST", path, query, body);
    }

    JsonNode patch(String path, Object body) throws Exception {
        return call("PATCH", path, Map.of(), body);
    }

    URI requestUri(String path, Map<String, ?> query) {
        var parts = query.entrySet().stream().filter(entry -> entry.getValue() != null)
            .map(entry -> "%s=%s".formatted(encode(entry.getKey()), encode(entry.getValue().toString()))).toList();
        var suffix = parts.isEmpty() ? "" : "?" + String.join("&", parts);
        return URI.create(base + path + suffix);
    }

    private JsonNode call(String method, String path, Map<String, ?> query, Object body) throws Exception {
        var request = HttpRequest.builder().uri(requestUri(path, query)).method(method).addHeader("Accept", "application/json");
        if (body != null) {
            request.body(HttpRequest.JsonRequestBody.of(body));
        }
        Response response;
        try {
            response = transport.execute(request);
        } catch (CancellationException e) {
            throw e;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(-1, "ServiceNow %s %s could not be completed. Check connectivity and the task timeout; a write may already have been accepted.".formatted(method, path), e);
        }
        JsonNode document = null;
        if (response.body().length != 0) {
            try {
                document = JacksonMapper.ofJson().readTree(response.body());
            } catch (Exception e) {
                if (response.status() >= 200 && response.status() < 300) {
                    throw new ApiException(response.status(), "ServiceNow %s %s returned invalid JSON. Check the instance URL and API version.".formatted(method, path));
                }
            }
        }
        if (
            response.status() < 200 || response.status() >= 300 || document != null && hasError(document.path("error"))
                || document != null && "failure".equalsIgnoreCase(document.path("status").asText())
        ) {
            var detail = document == null ? "" : errorDetail(document);
            for (var secret : response.secrets()) {
                if (!secret.isEmpty()) {
                    detail = detail.replace(secret, "[REDACTED]");
                }
            }
            if (detail.length() > 1000) {
                detail = detail.substring(0, 1000);
            }
            var advice = response.status() == 401 || response.status() == 403
                ? "Check credentials and the API/table ACLs."
                : response.status() == 404 ? "Check the instance, resource identifier and enabled API." : "Check the request and the instance's API rules.";
            throw new ApiException(response.status(), "ServiceNow %s %s returned HTTP %d%s. %s".formatted(method, path, response.status(), detail.isBlank() ? "" : ": " + detail, advice));
        }
        if (document == null || !document.isObject()) {
            throw new ApiException(response.status(), "ServiceNow %s %s returned an empty or non-object response. Check the instance URL and API version.".formatted(method, path));
        }
        return document;
    }

    private static boolean hasError(JsonNode error) {
        return !error.isMissingNode() && !error.isNull() && !(error.isContainerNode() && error.isEmpty())
            && !(error.isTextual() && error.asText().isBlank());
    }

    private static String errorDetail(JsonNode document) {
        var error = document.path("error");
        if (error.isObject()) {
            return String.join("; ", List.of(error.path("message").asText(""), error.path("detail").asText("")).stream().filter(value -> !value.isBlank()).toList());
        }
        return error.isTextual() ? error.asText() : document.path("message").asText("");
    }

    String recordUrl(String table, String sysId) {
        return "%s%s.do?sys_id=%s".formatted(base, table, encode(sysId));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static final class ApiException extends IllegalStateException {
        private final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        ApiException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
