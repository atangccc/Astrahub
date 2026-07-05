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
public class AstraHubWorldChatService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final ReactiveSettingFetcher settingFetcher;
    private final AstraHubCredentialReader credentialReader;

    public Mono<ProxyResult> get(String path, Map<String, String> query) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> requestBlocking("GET", path, query, "", tuple.getT1(), tuple.getT2()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<ProxyResult> post(String path, String body) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> requestBlocking("POST", path, Map.of(), body, tuple.getT1(), tuple.getT2()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<ProxyBytesResult> getBytes(String path, Map<String, String> query) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> requestBytesBlocking("GET", path, query, tuple.getT1(), tuple.getT2()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<JsonNode> readSetting(String key) {
        return settingFetcher.getSettingValue(key)
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .onErrorResume(error -> Mono.empty())
            .switchIfEmpty(Mono.fromSupplier(MAPPER::createObjectNode));
    }

    private ProxyResult requestBlocking(
        String method,
        String rawPath,
        Map<String, String> query,
        String rawBody,
        JsonNode connection,
        JsonNode credentials
    ) {
        String path = trim(rawPath);
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        String body = rawBody == null ? "" : rawBody;

        if (hubBaseUrl == null) {
            return ProxyResult.failed(400, "hubBaseUrl is invalid");
        }
        if (hubBaseUrl.isBlank()) {
            return ProxyResult.failed(400, "hubBaseUrl is required");
        }
        if (siteId.isBlank()) {
            return ProxyResult.failed(400, "siteId is required");
        }
        if (apiKey.isBlank()) {
            return ProxyResult.failed(400, "apiKey is required");
        }
        if (path.isBlank() || !path.startsWith("/v1/world-chat")) {
            return ProxyResult.failed(400, "world chat path is invalid");
        }

        try {
            SignedRequest signed = HubRequestSigner.signRequest(method, path, body, siteId, apiKey);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(buildRequestUri(hubBaseUrl, path, buildQueryString(query)))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature());
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }

            HttpResponse<String> response = HTTP_CLIENT.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            String responseBody = response.body() == null ? "" : response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ProxyResult(false, response.statusCode(), extractMessage(responseBody, "world chat request failed"), responseBody);
            }
            return new ProxyResult(true, response.statusCode(), "ok", responseBody);
        } catch (Exception error) {
            log.warn("[AstraHub] world chat proxy failed method={} path={}", method, path, error);
            return ProxyResult.failed(500, "world chat request failed: " + error.getMessage());
        }
    }

    private ProxyBytesResult requestBytesBlocking(
        String method,
        String rawPath,
        Map<String, String> query,
        JsonNode connection,
        JsonNode credentials
    ) {
        String path = trim(rawPath);
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");

        if (hubBaseUrl == null || hubBaseUrl.isBlank() || siteId.isBlank() || apiKey.isBlank()) {
            return ProxyBytesResult.failed(400, "world chat file proxy credentials are invalid");
        }
        if (path.isBlank() || !path.startsWith("/v1/world-chat")) {
            return ProxyBytesResult.failed(400, "world chat path is invalid");
        }

        try {
            SignedRequest signed = HubRequestSigner.signRequest(method, path, "", siteId, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(buildRequestUri(hubBaseUrl, path, buildQueryString(query)))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "image/png,image/jpeg,image/gif")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .GET()
                .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofByteArray()
            );
            byte[] body = response.body() == null ? new byte[0] : response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ProxyBytesResult(false, response.statusCode(), "world chat file request failed", contentType, body);
            }
            return new ProxyBytesResult(true, response.statusCode(), "ok", contentType, body);
        } catch (Exception error) {
            log.warn("[AstraHub] world chat file proxy failed method={} path={}", method, path, error);
            return ProxyBytesResult.failed(500, "world chat file request failed: " + error.getMessage());
        }
    }

    private static URI buildRequestUri(String hubBaseUrl, String rawPath, String queryString) {
        StringBuilder encoded = new StringBuilder(rawPath.length());
        int len = rawPath.length();
        int segmentStart = 1;
        encoded.append('/');
        for (int i = 1; i <= len; i++) {
            if (i == len || rawPath.charAt(i) == '/') {
                String segment = rawPath.substring(segmentStart, i);
                encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
                if (i < len) {
                    encoded.append('/');
                }
                segmentStart = i + 1;
            }
        }
        String full = hubBaseUrl + encoded;
        if (!queryString.isBlank()) {
            full = full + "?" + queryString;
        }
        return URI.create(full);
    }

    private static String buildQueryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        return query.entrySet().stream()
            .filter(entry -> !trim(entry.getKey()).isBlank())
            .filter(entry -> !trim(entry.getValue()).isBlank())
            .map(entry -> URLEncoder.encode(trim(entry.getKey()), StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(trim(entry.getValue()), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }

    private static String normalizeBaseUrl(String raw) {
        String value = trim(raw);
        if (value.isBlank()) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return uri.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractMessage(String body, String fallback) {
        try {
            JsonNode json = MAPPER.readTree(body);
            String message = trim(json.path("message").asText(""));
            if (!message.isBlank()) {
                return message;
            }
            String errorMessage = trim(json.path("error").path("message").asText(""));
            if (!errorMessage.isBlank()) {
                return errorMessage;
            }
        } catch (Exception ignored) {
        }
        return fallback;
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
        return value.isBlank() ? fallback : value;
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

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record ProxyResult(
        boolean success,
        int status,
        String message,
        String body
    ) {
        public static ProxyResult failed(int status, String message) {
            return new ProxyResult(false, status, message, "");
        }
    }

    public record ProxyBytesResult(
        boolean success,
        int status,
        String message,
        String contentType,
        byte[] body
    ) {
        public static ProxyBytesResult failed(int status, String message) {
            return new ProxyBytesResult(false, status, message, "application/json", new byte[0]);
        }
    }
}
