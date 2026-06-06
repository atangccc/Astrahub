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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubPushService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GRAPH_PUSH_PATH = "/v1/graph/push";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final ReactiveSettingFetcher settingFetcher;
    private final AstraHubCredentialReader credentialReader;

    public Mono<PushResult> pushGraph(AstraHubGraphTransformService.GraphPayload payload) {
        return pushPayload(payload, GRAPH_PUSH_PATH);
    }

    private Mono<PushResult> pushPayload(Object payload, String pushPath) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> pushBlocking(
                    payload,
                    pushPath,
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    private Mono<JsonNode> readSetting(String key) {
        return settingFetcher.getSettingValue(key)
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .onErrorResume(error -> Mono.empty())
            .switchIfEmpty(Mono.fromSupplier(MAPPER::createObjectNode));
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

    private PushResult pushBlocking(
        Object payload,
        String pushPath,
        JsonNode connection,
        JsonNode credentials
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteName = readString(connection, "siteName", "");
        String siteUrl = readString(connection, "siteUrl", "");
        String contactEmail = readString(connection, "contactEmail", "");
        String siteNodeName = readString(connection, "siteNodeName", "");
        String siteNodeAvatar = readString(connection, "siteNodeAvatar", "");
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");

        if (hubBaseUrl == null) {
            return PushResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return PushResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return PushResult.failed(400, "apiKey is required");
        }
        if (siteName.isEmpty()) {
            return PushResult.failed(400, "siteName is required before push");
        }
        if (siteUrl.isEmpty()) {
            return PushResult.failed(400, "siteUrl is required before push");
        }
        if (contactEmail.isEmpty()) {
            return PushResult.failed(400, "contactEmail is required before push");
        }
        if (siteNodeName.isEmpty()) {
            return PushResult.failed(400, "siteNodeName is required before push");
        }
        if (siteNodeAvatar.isEmpty()) {
            return PushResult.failed(400, "siteNodeAvatar is required before push");
        }

        try {
            String endpoint = hubBaseUrl + pushPath;
            String body = MAPPER.writeValueAsString(payload);
            SignedRequest signed = HubRequestSigner.signRequest("POST", pushPath, body, siteId, apiKey);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            HttpResponse<String> response = HTTP_CLIENT.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int status = response.statusCode();
            String responseBody = Optional.ofNullable(response.body()).orElse("");
            boolean success = status >= 200 && status < 300;
            String message = success ? "push success" : extractErrorMessage(responseBody, status);

            return new PushResult(
                success,
                status,
                message,
                shrink(responseBody, 2000),
                nowIso()
            );
        } catch (Exception error) {
            log.warn("[AstraHub] push request failed", error);
            return PushResult.failed(500, "push failed: " + error.getMessage());
        }
    }

    private static String extractErrorMessage(String responseBody, int status) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode error = root.get("error");
            if (error != null && error.isObject()) {
                String message = trim(error.path("message").asText(""));
                if (!message.isEmpty()) {
                    return message;
                }
            }
            String message = trim(root.path("message").asText(""));
            if (!message.isEmpty()) {
                return message;
            }
            String errorText = trim(root.path("error").asText(""));
            if (!errorText.isEmpty()) {
                return errorText;
            }
        } catch (Exception ignored) {
        }
        return "push failed with status " + status;
    }

    private static String normalizeBaseUrl(String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            return null;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return uri.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String shrink(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    private static String readString(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = trim(value.asText(""));
        return text.isEmpty() ? fallback : text;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record PushResult(
        boolean success,
        int status,
        String message,
        String responseBody,
        String pushedAt
    ) {
        public static PushResult failed(int status, String message) {
            return new PushResult(false, status, message, "", nowIso());
        }
    }
}
