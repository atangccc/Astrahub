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
public class AstraHubRegisterService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Mono<RegisterResult> register(RegisterCommand command) {
        return Mono.fromCallable(() -> registerBlocking(command))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 向 Hub 申请一张签发码，Hub 会把码发送到 contactEmail 指定的邮箱。
     */
    public Mono<InvitationRequestResult> requestInvitation(InvitationRequestCommand command) {
        return Mono.fromCallable(() -> requestInvitationBlocking(command))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 使用邮箱收到的签发码 + 站点信息完成真正的注册。
     */
    public Mono<RegisterResult> registerWithInvitation(InvitationRegisterCommand command) {
        return Mono.fromCallable(() -> registerWithInvitationBlocking(command))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private RegisterResult registerBlocking(RegisterCommand command) {
        String hubBaseUrl = trim(command.hubBaseUrl());
        String siteName = trim(command.siteName());
        String siteUrl = trim(command.siteUrl());
        String siteDescription = trim(command.siteDescription());
        String siteRssUrl = trim(command.siteRssUrl());
        String siteAvatarUrl = trim(command.siteAvatarUrl());
        String registerToken = trim(command.registerToken());
        String contactEmail = trim(command.contactEmail());
        String siteNodeName = trim(command.siteNodeName());
        String siteNodeAvatar = trim(command.siteNodeAvatar());

        if (hubBaseUrl.isEmpty()) {
            return RegisterResult.failed(400, "hubBaseUrl is required");
        }
        if (siteName.isEmpty()) {
            return RegisterResult.failed(400, "siteName is required");
        }
        if (siteUrl.isEmpty()) {
            return RegisterResult.failed(400, "siteUrl is required");
        }
        if (contactEmail.isEmpty()) {
            return RegisterResult.failed(400, "contactEmail is required");
        }
        if (siteNodeName.isEmpty()) {
            return RegisterResult.failed(400, "siteNodeName is required");
        }
        if (siteNodeAvatar.isEmpty()) {
            return RegisterResult.failed(400, "siteNodeAvatar is required");
        }

        String normalizedBaseUrl = normalizeBaseUrl(hubBaseUrl);
        if (normalizedBaseUrl == null) {
            return RegisterResult.failed(400, "hubBaseUrl is invalid");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", siteName);
            payload.put("url", siteUrl);
            payload.put("description", siteDescription);
            payload.put("rssUrl", siteRssUrl);
            payload.put("avatarUrl", siteAvatarUrl);
            payload.put("contactEmail", contactEmail);
            payload.put("nodeName", siteNodeName);
            payload.put("nodeAvatar", siteNodeAvatar);

            String requestBody = MAPPER.writeValueAsString(payload);
            String endpoint = normalizedBaseUrl + "/v1/sites/register";

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

            if (!registerToken.isEmpty()) {
                builder.header("X-BP-Register-Token", registerToken);
            }

            HttpResponse<String> response = HTTP_CLIENT.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            Map<String, Object> bodyJson = parseObject(body);

            if (status == 200 || status == 201) {
                String siteId = trim(anyToString(bodyJson.get("siteId")));
                String apiKey = trim(anyToString(bodyJson.get("apiKey")));
                String createdAt = trim(anyToString(bodyJson.get("createdAt")));
                String nodeName = trim(anyToString(bodyJson.get("nodeName")));
                String category = trim(anyToString(bodyJson.get("category")));
                String nodeAvatar = trim(anyToString(bodyJson.get("nodeAvatar")));
                if (nodeName.isEmpty()) {
                    nodeName = category;
                }
                if (siteId.isEmpty() || apiKey.isEmpty()) {
                    return RegisterResult.failed(status, "register response missing siteId/apiKey");
                }
                return RegisterResult.success(
                    status,
                    "registered",
                    siteId,
                    apiKey,
                    createdAt,
                    nodeName,
                    category,
                    nodeAvatar
                );
            }

            String message = extractErrorMessage(bodyJson);
            if (message.isEmpty()) {
                message = "register failed with status " + status;
            }
            return RegisterResult.failed(status, message);
        } catch (IOException e) {
            log.warn("[AstraHub] register request IO error", e);
            return RegisterResult.failed(502, "register request IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RegisterResult.failed(500, "register request interrupted");
        } catch (Exception e) {
            log.warn("[AstraHub] register request error", e);
            return RegisterResult.failed(500, "register request error: " + e.getMessage());
        }
    }

    private InvitationRequestResult requestInvitationBlocking(InvitationRequestCommand command) {
        String hubBaseUrl = trim(command.hubBaseUrl());
        String contactEmail = trim(command.contactEmail());
        String siteUrl = trim(command.siteUrl());

        if (hubBaseUrl.isEmpty()) {
            return InvitationRequestResult.failed(400, "hubBaseUrl is required");
        }
        if (contactEmail.isEmpty()) {
            return InvitationRequestResult.failed(400, "contactEmail is required");
        }

        String normalizedBaseUrl = normalizeBaseUrl(hubBaseUrl);
        if (normalizedBaseUrl == null) {
            return InvitationRequestResult.failed(400, "hubBaseUrl is invalid");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contactEmail", contactEmail);
            if (!siteUrl.isEmpty()) {
                payload.put("siteUrl", siteUrl);
            }

            String requestBody = MAPPER.writeValueAsString(payload);
            String endpoint = normalizedBaseUrl + "/v1/sites/invitations/apply";

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

            if (status == 200 || status == 201) {
                String expiresAt = trim(anyToString(bodyJson.get("expiresAt")));
                String cooldownUntil = trim(anyToString(bodyJson.get("cooldownUntil")));
                return InvitationRequestResult.success(status, "code sent to mailbox", expiresAt, cooldownUntil);
            }

            String message = extractErrorMessage(bodyJson);
            if (message.isEmpty()) {
                message = "request invitation failed with status " + status;
            }
            return InvitationRequestResult.failed(status, message);
        } catch (IOException e) {
            log.warn("[AstraHub] request invitation IO error", e);
            return InvitationRequestResult.failed(502, "request invitation IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InvitationRequestResult.failed(500, "request invitation interrupted");
        } catch (Exception e) {
            log.warn("[AstraHub] request invitation error", e);
            return InvitationRequestResult.failed(500, "request invitation error: " + e.getMessage());
        }
    }

    private RegisterResult registerWithInvitationBlocking(InvitationRegisterCommand command) {
        String hubBaseUrl = trim(command.hubBaseUrl());
        String invitationCode = trim(command.invitationCode());
        String siteName = trim(command.siteName());
        String siteUrl = trim(command.siteUrl());
        String siteDescription = trim(command.siteDescription());
        String siteRssUrl = trim(command.siteRssUrl());
        String siteAvatarUrl = trim(command.siteAvatarUrl());
        String contactEmail = trim(command.contactEmail());
        String siteNodeName = trim(command.siteNodeName());
        String siteNodeAvatar = trim(command.siteNodeAvatar());

        if (hubBaseUrl.isEmpty()) {
            return RegisterResult.failed(400, "hubBaseUrl is required");
        }
        if (invitationCode.isEmpty()) {
            return RegisterResult.failed(400, "invitationCode is required");
        }
        if (siteName.isEmpty()) {
            return RegisterResult.failed(400, "siteName is required");
        }
        if (siteUrl.isEmpty()) {
            return RegisterResult.failed(400, "siteUrl is required");
        }
        if (contactEmail.isEmpty()) {
            return RegisterResult.failed(400, "contactEmail is required");
        }
        if (siteNodeName.isEmpty()) {
            return RegisterResult.failed(400, "siteNodeName is required");
        }
        if (siteNodeAvatar.isEmpty()) {
            return RegisterResult.failed(400, "siteNodeAvatar is required");
        }

        String normalizedBaseUrl = normalizeBaseUrl(hubBaseUrl);
        if (normalizedBaseUrl == null) {
            return RegisterResult.failed(400, "hubBaseUrl is invalid");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", siteName);
            payload.put("url", siteUrl);
            payload.put("description", siteDescription);
            payload.put("rssUrl", siteRssUrl);
            payload.put("avatarUrl", siteAvatarUrl);
            payload.put("contactEmail", contactEmail);
            payload.put("nodeName", siteNodeName);
            payload.put("nodeAvatar", siteNodeAvatar);

            String requestBody = MAPPER.writeValueAsString(payload);
            String endpoint = normalizedBaseUrl + "/v1/sites/register";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-BP-Invitation-Code", invitationCode)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            Map<String, Object> bodyJson = parseObject(body);

            if (status == 200 || status == 201) {
                String siteId = trim(anyToString(bodyJson.get("siteId")));
                String apiKey = trim(anyToString(bodyJson.get("apiKey")));
                String createdAt = trim(anyToString(bodyJson.get("createdAt")));
                String nodeName = trim(anyToString(bodyJson.get("nodeName")));
                String category = trim(anyToString(bodyJson.get("category")));
                String nodeAvatar = trim(anyToString(bodyJson.get("nodeAvatar")));
                if (nodeName.isEmpty()) {
                    nodeName = category;
                }
                if (siteId.isEmpty() || apiKey.isEmpty()) {
                    return RegisterResult.failed(status, "register response missing siteId/apiKey");
                }
                return RegisterResult.success(
                    status,
                    "registered",
                    siteId,
                    apiKey,
                    createdAt,
                    nodeName,
                    category,
                    nodeAvatar
                );
            }

            String message = extractErrorMessage(bodyJson);
            if (message.isEmpty()) {
                message = "register with invitation failed with status " + status;
            }
            return RegisterResult.failed(status, message);
        } catch (IOException e) {
            log.warn("[AstraHub] register with invitation IO error", e);
            return RegisterResult.failed(502, "register with invitation IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RegisterResult.failed(500, "register with invitation interrupted");
        } catch (Exception e) {
            log.warn("[AstraHub] register with invitation error", e);
            return RegisterResult.failed(500, "register with invitation error: " + e.getMessage());
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

    public record RegisterCommand(
        String hubBaseUrl,
        String registerToken,
        String siteName,
        String siteUrl,
        String siteDescription,
        String siteRssUrl,
        String siteAvatarUrl,
        String contactEmail,
        String siteNodeName,
        String siteNodeAvatar
    ) {
    }

    public record RegisterResult(
        boolean success,
        int status,
        String message,
        String siteId,
        String apiKey,
        String createdAt,
        String nodeName,
        String category,
        String nodeAvatar
    ) {
        public static RegisterResult success(int status, String message, String siteId,
                                             String apiKey, String createdAt, String nodeName,
                                             String category, String nodeAvatar) {
            return new RegisterResult(
                true,
                status,
                message,
                siteId,
                apiKey,
                createdAt,
                nodeName,
                category,
                nodeAvatar
            );
        }

        public static RegisterResult failed(int status, String message) {
            return new RegisterResult(false, status, message, "", "", "", "", "", "");
        }
    }

    public record InvitationRequestCommand(
        String hubBaseUrl,
        String contactEmail,
        String siteUrl
    ) {
    }

    public record InvitationRequestResult(
        boolean success,
        int status,
        String message,
        String expiresAt,
        String cooldownUntil
    ) {
        public static InvitationRequestResult success(int status, String message,
                                                      String expiresAt, String cooldownUntil) {
            return new InvitationRequestResult(true, status, message, expiresAt, cooldownUntil);
        }

        public static InvitationRequestResult failed(int status, String message) {
            return new InvitationRequestResult(false, status, message, "", "");
        }
    }

    public record InvitationRegisterCommand(
        String hubBaseUrl,
        String invitationCode,
        String siteName,
        String siteUrl,
        String siteDescription,
        String siteRssUrl,
        String siteAvatarUrl,
        String contactEmail,
        String siteNodeName,
        String siteNodeAvatar
    ) {
    }
}
