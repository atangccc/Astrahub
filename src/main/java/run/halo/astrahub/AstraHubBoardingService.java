package run.halo.astrahub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class AstraHubBoardingService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Mono<SendCodeResult> sendCode(SendCodeCommand command) {
        return Mono.fromCallable(() -> sendCodeBlocking(command))
            .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<RestoreResult> restore(RestoreCommand command) {
        return Mono.fromCallable(() -> restoreBlocking(command))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private SendCodeResult sendCodeBlocking(SendCodeCommand command) {
        String hubBaseUrl = trim(command.hubBaseUrl());
        String contactEmail = trim(command.contactEmail());

        if (hubBaseUrl.isEmpty()) {
            return SendCodeResult.failed(400, "hubBaseUrl is required");
        }
        if (contactEmail.isEmpty()) {
            return SendCodeResult.failed(400, "contactEmail is required");
        }

        String normalizedBaseUrl = normalizeBaseUrl(hubBaseUrl);
        if (normalizedBaseUrl == null) {
            return SendCodeResult.failed(400, "hubBaseUrl is invalid");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contactEmail", contactEmail);

            String requestBody = MAPPER.writeValueAsString(payload);
            String endpoint = normalizedBaseUrl + "/v1/sites/boarding/send-code";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            Map<String, Object> bodyJson = parseObject(body);

            if (status >= 200 && status < 300) {
                return SendCodeResult.success(
                    status,
                    "code sent",
                    trim(anyToString(bodyJson.get("expiresAt")))
                );
            }

            String message = extractErrorMessage(bodyJson);
            if (message.isEmpty()) {
                message = "send code failed with status " + status;
            }
            return SendCodeResult.failed(status, message);
        } catch (IOException e) {
            log.warn("[AstraHub] send code IO error", e);
            return SendCodeResult.failed(502, "send code IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendCodeResult.failed(500, "send code interrupted");
        } catch (Exception e) {
            log.warn("[AstraHub] send code error", e);
            return SendCodeResult.failed(500, "send code error: " + e.getMessage());
        }
    }

    private RestoreResult restoreBlocking(RestoreCommand command) {
        String hubBaseUrl = trim(command.hubBaseUrl());
        String contactEmail = trim(command.contactEmail());
        String code = trim(command.code());

        if (hubBaseUrl.isEmpty()) {
            return RestoreResult.failed(400, "hubBaseUrl is required");
        }
        if (contactEmail.isEmpty()) {
            return RestoreResult.failed(400, "contactEmail is required");
        }
        if (code.isEmpty()) {
            return RestoreResult.failed(400, "code is required");
        }

        String normalizedBaseUrl = normalizeBaseUrl(hubBaseUrl);
        if (normalizedBaseUrl == null) {
            return RestoreResult.failed(400, "hubBaseUrl is invalid");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contactEmail", contactEmail);
            payload.put("code", code);

            String requestBody = MAPPER.writeValueAsString(payload);
            String endpoint = normalizedBaseUrl + "/v1/sites/boarding/restore";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            Map<String, Object> bodyJson = parseObject(body);

            if (status >= 200 && status < 300) {
                String siteId = trim(anyToString(bodyJson.get("siteId")));
                String apiKey = trim(anyToString(bodyJson.get("apiKey")));
                String siteName = trim(anyToString(bodyJson.get("siteName")));
                String siteUrl = trim(anyToString(bodyJson.get("siteUrl")));
                String restoredEmail = trim(anyToString(bodyJson.get("contactEmail")));
                String description = trim(anyToString(bodyJson.get("description")));
                String rssUrl = trim(anyToString(bodyJson.get("rssUrl")));
                String nodeName = trim(anyToString(bodyJson.get("nodeName")));
                String category = trim(anyToString(bodyJson.get("category")));
                String nodeAvatar = trim(anyToString(bodyJson.get("nodeAvatar")));
                if (nodeName.isEmpty()) {
                    nodeName = category;
                }
                String createdAt = trim(anyToString(bodyJson.get("createdAt")));
                if (siteId.isEmpty() || apiKey.isEmpty()) {
                    return RestoreResult.failed(status, "restore response missing siteId/apiKey");
                }
                return RestoreResult.success(
                    status,
                    "restore success",
                    siteId,
                    apiKey,
                    siteName,
                    siteUrl,
                    restoredEmail,
                    description,
                    rssUrl,
                    nodeName,
                    category,
                    nodeAvatar,
                    createdAt
                );
            }

            String message = extractErrorMessage(bodyJson);
            if (message.isEmpty()) {
                message = "restore failed with status " + status;
            }
            return RestoreResult.failed(status, message);
        } catch (IOException e) {
            log.warn("[AstraHub] restore IO error", e);
            return RestoreResult.failed(502, "restore IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RestoreResult.failed(500, "restore interrupted");
        } catch (Exception e) {
            log.warn("[AstraHub] restore error", e);
            return RestoreResult.failed(500, "restore error: " + e.getMessage());
        }
    }

    private static String extractErrorMessage(Map<String, Object> body) {
        Object error = body.get("error");
        if (error instanceof String s) {
            return trim(s);
        }
        if (error instanceof Map<?, ?> m) {
            Object message = m.get("message");
            if (message instanceof String s) {
                return trim(s);
            }
        }
        Object message = body.get("message");
        if (message instanceof String s) {
            return trim(s);
        }
        return "";
    }

    private static String normalizeBaseUrl(String raw) {
        String trimmed = trim(raw);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return null;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            URI uri = URI.create(trimmed);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return uri.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("raw", json);
            return fallback;
        }
    }

    private static String anyToString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record SendCodeCommand(
        String hubBaseUrl,
        String contactEmail
    ) {
    }

    public record RestoreCommand(
        String hubBaseUrl,
        String contactEmail,
        String code
    ) {
    }

    public record SendCodeResult(
        boolean success,
        int status,
        String message,
        String expiresAt
    ) {
        public static SendCodeResult success(int status, String message, String expiresAt) {
            return new SendCodeResult(true, status, message, expiresAt);
        }

        public static SendCodeResult failed(int status, String message) {
            return new SendCodeResult(false, status, message, "");
        }
    }

    public record RestoreResult(
        boolean success,
        int status,
        String message,
        String siteId,
        String apiKey,
        String siteName,
        String siteUrl,
        String contactEmail,
        String description,
        String rssUrl,
        String nodeName,
        String category,
        String nodeAvatar,
        String createdAt
    ) {
        public static RestoreResult success(
            int status,
            String message,
            String siteId,
            String apiKey,
            String siteName,
            String siteUrl,
            String contactEmail,
            String description,
            String rssUrl,
            String nodeName,
            String category,
            String nodeAvatar,
            String createdAt
        ) {
            return new RestoreResult(
                true,
                status,
                message,
                siteId,
                apiKey,
                siteName,
                siteUrl,
                contactEmail,
                description,
                rssUrl,
                nodeName,
                category,
                nodeAvatar,
                createdAt
            );
        }

        public static RestoreResult failed(int status, String message) {
            return new RestoreResult(false, status, message, "", "", "", "", "", "", "", "", "", "", "");
        }
    }
}
