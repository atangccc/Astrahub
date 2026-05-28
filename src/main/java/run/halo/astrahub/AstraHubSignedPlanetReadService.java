package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.astrahub.util.HubRequestSigner;
import run.halo.astrahub.util.HubRequestSigner.SignedRequest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubSignedPlanetReadService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final ReactiveSettingFetcher settingFetcher;

    public Mono<SignedReadResult> fetch(String path, Map<String, String> query) {
        return Mono.zip(readSetting("connection"), readSetting("credentials"))
            .flatMap(tuple -> Mono.fromCallable(() -> fetchBlocking(path, query, tuple.getT1(), tuple.getT2()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<JsonNode> readSetting(String key) {
        return settingFetcher.getSettingValue(key)
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .onErrorResume(error -> Mono.empty())
            .switchIfEmpty(Mono.fromSupplier(MAPPER::createObjectNode));
    }

    private SignedReadResult fetchBlocking(String rawPath, Map<String, String> query, JsonNode connection, JsonNode credentials) {
        String path = trim(rawPath);
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");

        if (hubBaseUrl == null) {
            return SignedReadResult.failed(400, "hubBaseUrl is invalid");
        }
        if (hubBaseUrl.isEmpty()) {
            return SignedReadResult.failed(400, "hubBaseUrl is required");
        }
        if (siteId.isEmpty()) {
            return SignedReadResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return SignedReadResult.failed(400, "apiKey is required");
        }
        if (path.isEmpty() || !path.startsWith("/")) {
            return SignedReadResult.failed(400, "path is invalid");
        }

        String queryString = buildQueryString(query);

        try {
            SignedRequest signed = HubRequestSigner.signRequest("GET", path, "", siteId, apiKey);
            // Build the URI in two steps:
            //   1. signature canonical path uses the raw decoded path (matches hub r.URL.Path)
            //   2. actual HTTP request URI must be percent-encoded for unicode/space safety
            URI requestUri = buildRequestUri(hubBaseUrl, path, queryString);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(requestUri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            String responseBody = response.body() == null ? "" : response.body();
            if (statusCode < 200 || statusCode >= 300) {
                return SignedReadResult.failed(statusCode, extractMessage(responseBody, "load failed"));
            }
            return new SignedReadResult(true, statusCode, "ok", responseBody);
        } catch (Exception error) {
            log.warn("[AstraHub] signed planet read failed path={}", path, error);
            return SignedReadResult.failed(500, "load failed: " + error.getMessage());
        }
    }

    private static String buildQueryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        return query.entrySet().stream()
            .filter(entry -> !trim(entry.getKey()).isEmpty())
            .filter(entry -> !trim(entry.getValue()).isEmpty())
            .map(entry -> URLEncoder.encode(trim(entry.getKey()), StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(trim(entry.getValue()), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }

    /**
     * Build a request URI that is wire-safe for unicode path segments.
     *
     * <p>{@code URI.create} rejects characters outside the ASCII subset, so
     * paths like {@code /v1/graph/nodes/中文节点} would throw. We split the
     * path by '/', percent-encode each segment, then assemble the final URI.
     * Query string is already percent-encoded by {@link #buildQueryString}.</p>
     */
    private static URI buildRequestUri(String hubBaseUrl, String rawPath, String queryString) {
        StringBuilder encoded = new StringBuilder(rawPath.length());
        // rawPath always starts with '/'
        int len = rawPath.length();
        int segStart = 1;
        encoded.append('/');
        for (int i = 1; i <= len; i++) {
            if (i == len || rawPath.charAt(i) == '/') {
                String segment = rawPath.substring(segStart, i);
                encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
                if (i < len) {
                    encoded.append('/');
                }
                segStart = i + 1;
            }
        }
        String full = hubBaseUrl + encoded;
        if (!queryString.isEmpty()) {
            full = full + "?" + queryString;
        }
        return URI.create(full);
    }

    private static String normalizeBaseUrl(String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            return "";
        }
        value = value.replaceAll("/+$", "");
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null;
        }
        return value;
    }

    private static String readString(JsonNode node, String fieldName, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        JsonNode child = node.path(fieldName);
        if (child.isMissingNode() || child.isNull()) {
            return fallback;
        }
        String value = trim(child.asText(""));
        return value.isEmpty() ? fallback : value;
    }

    private static String extractMessage(String body, String fallback) {
        try {
            JsonNode json = MAPPER.readTree(body);
            String message = trim(json.path("message").asText(""));
            if (!message.isEmpty()) {
                return message;
            }
            String errorMessage = trim(json.path("error").path("message").asText(""));
            if (!errorMessage.isEmpty()) {
                return errorMessage;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static JsonNode toJsonNode(Object value) throws Exception {
        if (value == null) {
            return MAPPER.createObjectNode();
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        String raw = value.toString();
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(raw);
    }

    public record SignedReadResult(
        boolean success,
        int status,
        String message,
        String body
    ) {
        public static SignedReadResult failed(int status, String message) {
            return new SignedReadResult(false, status, message, "");
        }
    }
}
