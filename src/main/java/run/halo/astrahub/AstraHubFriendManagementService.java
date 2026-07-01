package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.astrahub.model.LinkGroup;
import run.halo.astrahub.model.FriendInvitation;
import run.halo.astrahub.util.HubRequestSigner;
import run.halo.astrahub.util.HubRequestSigner.SignedRequest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubFriendManagementService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private static final String HUB_INBOX_PATH = "/v1/friend-invitations/inbox";
    private static final String HUB_OUTBOX_PATH = "/v1/friend-invitations/outbox";
    private static final String HUB_CREATE_PATH = "/v1/friend-invitations";
    private static final String HUB_REVIEW_PATH_TEMPLATE = "/v1/friend-invitations/%s/review";
    private static final String HUB_ACK_PATH_TEMPLATE = "/v1/friend-invitations/%s/ack";
    private static final String HUB_CANCEL_PATH_TEMPLATE = "/v1/friend-invitations/%s/cancel";
    private static final String HUB_DELETE_PATH_TEMPLATE = "/v1/friend-invitations/%s/delete";
    private static final String HUB_REMOVE_RELATION_PATH_TEMPLATE = "/v1/friend-relations/%s/remove";
    private static final String HUB_REMOVE_FOLLOW_PATH_TEMPLATE = "/v1/friend-follows/%s/remove";
    private static final String HUB_SITE_LOOKUP_PATH = "/v1/sites/lookup";
    private static final String HUB_SITE_RELATION_BATCH_PATH = "/v1/relations/sites/batch";
    private static final String HUB_WS_TOKEN_PATH = "/v1/ws-token";

    private final ReactiveSettingFetcher settingFetcher;
    private final ReactiveExtensionClient client;
    private final AstraHubFriendSettingsService friendSettingsService;
    private final AstraHubFriendLinkReconcileService friendLinkReconcileService;
    private final AstraHubCredentialReader credentialReader;

    public Mono<FriendInvitationsQueryResult> list(FriendInvitationsQuery query) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> listBlocking(
                    query,
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<LinkGroupOptionsResult> listLinkGroups() {
        return client.listAll(LinkGroup.class, new ListOptions(), Sort.by("spec.priority"))
            .collectList()
            .map(groups -> {
                List<LinkGroupOption> items = new ArrayList<>();
                for (LinkGroup group : groups) {
                    String name = trim(group.getMetadata() == null ? null : group.getMetadata().getName());
                    if (name.isEmpty()) {
                        continue;
                    }
                    String displayName = trim(group.getSpec() == null ? null : group.getSpec().getDisplayName());
                    items.add(new LinkGroupOption(name, displayName.isEmpty() ? name : displayName));
                }
                return new LinkGroupOptionsResult(true, 200, "ok", items);
            })
            .onErrorResume(error -> {
                log.warn("[AstraHub] list link groups failed", error);
                return Mono.just(LinkGroupOptionsResult.failed(500, "load link groups failed"));
            });
    }

    public Mono<ReviewResult> review(ReviewCommand command) {
        return Mono.zip(
                readSetting("connection"),
                credentialReader.readCredentials(),
                friendSettingsService.readInvitationSettings()
            )
            .flatMap(tuple -> Mono.fromCallable(() -> reviewBlocking(
                    command,
                    tuple.getT1(),
                    tuple.getT2(),
                    tuple.getT3()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            )
            .flatMap(result -> reconcileLocalLinkAfterApproval(command, result));
    }

    private Mono<ReviewResult> reconcileLocalLinkAfterApproval(ReviewCommand command, ReviewResult result) {
        if (!command.approved() || result == null || !result.success() || result.invitation() == null) {
            return Mono.just(result);
        }
        String reviewerSiteId = trim(result.invitation().toSite() == null ? null : result.invitation().toSite().siteId());
        return friendLinkReconcileService.reconcile(result.invitation(), reviewerSiteId)
            .doOnNext(reconcile -> {
                if (!reconcile.success()) {
                    log.warn("[AstraHub] approved invitation {} but local link reconcile failed: {}",
                        result.invitation().inviteId(), reconcile.message());
                }
            })
            .onErrorResume(error -> {
                log.warn("[AstraHub] approved invitation {} but local link reconcile threw",
                    result.invitation().inviteId(), error);
                return Mono.empty();
            })
            .thenReturn(result);
    }

    public Mono<AckResult> ack(AckCommand command) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> ackBlocking(
                    command,
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<CancelResult> cancel(CancelCommand command) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> cancelBlocking(
                    command,
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<DeleteResult> delete(DeleteCommand command) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> deleteBlocking(
                    command,
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<RemoveFriendRelationResult> removeFriendRelation(RemoveFriendRelationCommand command) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> removeFriendRelationBlocking(
                    command,
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<RemoveFriendRelationResult> removeOwnFriendFollow(RemoveFriendRelationCommand command) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> removeOwnFriendFollowBlocking(
                    command,
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<CreateResult> create(CreateCommand command) {
        return Mono.zip(
                readSetting("connection"),
                credentialReader.readCredentials(),
                friendSettingsService.readInvitationSettings()
            )
            .flatMap(tuple -> Mono.fromCallable(() -> createBlocking(
                    command,
                    tuple.getT1(),
                    tuple.getT2(),
                    tuple.getT3()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<SiteLookupResult> lookupSiteByUrl(String rawUrl) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> lookupSiteByUrlBlocking(
                    rawUrl,
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<SiteRelationBatchResult> resolveSiteRelations(List<String> targetUrls) {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> resolveSiteRelationsBlocking(
                    targetUrls,
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<RealtimeTokenResult> issueRealtimeToken() {
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials())
            .flatMap(tuple -> Mono.fromCallable(() -> issueRealtimeTokenBlocking(
                    tuple.getT1(),
                    tuple.getT2()
                ))
                .subscribeOn(Schedulers.boundedElastic())
            );
    }

    public Mono<Void> deleteLocalInvitation(String name) {
        return client.fetch(FriendInvitation.class, name)
            .flatMap(found -> found == null ? Mono.empty() : client.delete(found).then())
            .switchIfEmpty(Mono.empty());
    }

    private Mono<JsonNode> readSetting(String key) {
        return settingFetcher.getSettingValue(key)
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .onErrorResume(error -> Mono.empty())
            .switchIfEmpty(Mono.fromSupplier(MAPPER::createObjectNode));
    }

    private FriendInvitationsQueryResult listBlocking(
        FriendInvitationsQuery query,
        JsonNode connection,
        JsonNode credentials
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        String box = normalizeBox(query.box());
        String invitationStatus = trim(query.status());

        if (hubBaseUrl == null) {
            return FriendInvitationsQueryResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return FriendInvitationsQueryResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return FriendInvitationsQueryResult.failed(400, "apiKey is required");
        }

        String path = "outbox".equals(box) ? HUB_OUTBOX_PATH : HUB_INBOX_PATH;
        String endpoint = hubBaseUrl + path;
        if (!invitationStatus.isEmpty()) {
            endpoint += "?status=" + URLEncoder.encode(invitationStatus, StandardCharsets.UTF_8);
        }

        try {
            SignedRequest signed = HubRequestSigner.signRequest("GET", path, "", siteId, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
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
            if (isRedirect(statusCode)) {
                return FriendInvitationsQueryResult.failed(statusCode, buildRedirectMessage(response, "load failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return FriendInvitationsQueryResult.failed(statusCode, extractMessage(json, "load failed"));
            }

            List<FriendInvitationItem> items = new ArrayList<>();
            JsonNode array = json.path("items");
            if (array.isArray()) {
                for (JsonNode node : array) {
                    items.add(toInvitationItem(node));
                }
            }

            return new FriendInvitationsQueryResult(
                true,
                statusCode,
                "ok",
                box,
                text(json, "generatedAt"),
                json.path("total").asInt(items.size()),
                items
            );
        } catch (Exception error) {
            log.warn("[AstraHub] query friend invitations failed", error);
            return FriendInvitationsQueryResult.failed(500, "load failed: " + error.getMessage());
        }
    }

    private CreateResult createBlocking(
        CreateCommand command,
        JsonNode connection,
        JsonNode credentials,
        AstraHubFriendSettingsService.InvitationSettings invitationSettings
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        String targetSiteId = trim(command.toSiteId());

        if (hubBaseUrl == null) {
            return CreateResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return CreateResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return CreateResult.failed(400, "apiKey is required");
        }
        if (!invitationSettings.allowOutgoingInvitations()) {
            return CreateResult.failed(403, "当前站点已关闭发起友链邀请");
        }
        if (targetSiteId.isEmpty()) {
            return CreateResult.failed(400, "toSiteId is required");
        }

        try {
            JsonNode bodyNode = MAPPER.createObjectNode()
                .put("toSiteId", targetSiteId)
                .put("message", trim(command.message()))
                .put("linkGroupName", trim(command.linkGroupName()))
                .put("idempotencyKey", AstraHubFriendInvitationProtocol.buildIdempotencyKey(siteId, targetSiteId, command.message()));
            String body = MAPPER.writeValueAsString(bodyNode);
            SignedRequest signed = HubRequestSigner.signRequest("POST", HUB_CREATE_PATH, body, siteId, apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(hubBaseUrl + HUB_CREATE_PATH))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header(AstraHubFriendInvitationProtocol.HEADER_SITE_ID, signed.siteId())
                .header(AstraHubFriendInvitationProtocol.HEADER_TIMESTAMP, signed.timestamp())
                .header(AstraHubFriendInvitationProtocol.HEADER_NONCE, signed.nonce())
                .header(AstraHubFriendInvitationProtocol.HEADER_SIGNATURE, signed.signature())
                .header(AstraHubFriendInvitationProtocol.HEADER_IDEMPOTENCY_KEY,
                    AstraHubFriendInvitationProtocol.buildIdempotencyKey(siteId, targetSiteId, command.message()))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            if (isRedirect(statusCode)) {
                return CreateResult.failed(statusCode, buildRedirectMessage(response, "create failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return CreateResult.failed(statusCode, extractMessage(json, "create failed"));
            }

            FriendInvitationItem invitation = toInvitationItem(json.path("invitation"));
            return new CreateResult(true, statusCode, extractMessage(json, "ok"), invitation);
        } catch (Exception error) {
            log.warn("[AstraHub] create friend invitation failed", error);
            return CreateResult.failed(500, "create failed: " + error.getMessage());
        }
    }

    private ReviewResult reviewBlocking(
        ReviewCommand command,
        JsonNode connection,
        JsonNode credentials,
        AstraHubFriendSettingsService.InvitationSettings invitationSettings
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        String inviteId = trim(command.inviteId());
        String linkGroupName = trim(command.linkGroupName());

        if (hubBaseUrl == null) {
            return ReviewResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return ReviewResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return ReviewResult.failed(400, "apiKey is required");
        }
        if (!invitationSettings.allowIncomingInvitations()) {
            return ReviewResult.failed(403, "当前站点已关闭接收友链邀请");
        }
        if (inviteId.isEmpty()) {
            return ReviewResult.failed(400, "inviteId is required");
        }
        if (command.approved() && linkGroupName.isEmpty()) {
            linkGroupName = trim(invitationSettings.defaultInvitationLinkGroup());
        }

        String path = HUB_REVIEW_PATH_TEMPLATE.formatted(inviteId);
        String endpoint = hubBaseUrl + path;

        try {
            JsonNode bodyNode = MAPPER.createObjectNode()
                .put("approved", command.approved())
                .put("reason", trim(command.reason()))
                .put("linkGroupName", linkGroupName);
            String body = MAPPER.writeValueAsString(bodyNode);
            SignedRequest signed = HubRequestSigner.signRequest("POST", path, body, siteId, apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            if (isRedirect(statusCode)) {
                return ReviewResult.failed(statusCode, buildRedirectMessage(response, "review failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return ReviewResult.failed(statusCode, extractMessage(json, "review failed"));
            }

            FriendInvitationItem invitation = toInvitationItem(json.path("invitation"));
            return new ReviewResult(true, statusCode, extractMessage(json, "ok"), invitation);
        } catch (Exception error) {
            log.warn("[AstraHub] review friend invitation failed", error);
            return ReviewResult.failed(500, "review failed: " + error.getMessage());
        }
    }

    private AckResult ackBlocking(
        AckCommand command,
        JsonNode connection,
        JsonNode credentials
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        String inviteId = trim(command.inviteId());

        if (hubBaseUrl == null) {
            return AckResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return AckResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return AckResult.failed(400, "apiKey is required");
        }
        if (inviteId.isEmpty()) {
            return AckResult.failed(400, "inviteId is required");
        }

        String path = HUB_ACK_PATH_TEMPLATE.formatted(inviteId);
        String endpoint = hubBaseUrl + path;

        try {
            JsonNode bodyNode = MAPPER.createObjectNode()
                .put("lastError", trim(command.lastError()));
            String body = MAPPER.writeValueAsString(bodyNode);
            SignedRequest signed = HubRequestSigner.signRequest("POST", path, body, siteId, apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            if (isRedirect(statusCode)) {
                return AckResult.failed(statusCode, buildRedirectMessage(response, "ack failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return AckResult.failed(statusCode, extractMessage(json, "ack failed"));
            }

            FriendInvitationItem invitation = toInvitationItem(json.path("invitation"));
            return new AckResult(true, statusCode, extractMessage(json, "ok"), invitation);
        } catch (Exception error) {
            log.warn("[AstraHub] ack friend invitation failed", error);
            return AckResult.failed(500, "ack failed: " + error.getMessage());
        }
    }

    private CancelResult cancelBlocking(
        CancelCommand command,
        JsonNode connection,
        JsonNode credentials
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        String inviteId = trim(command.inviteId());

        if (hubBaseUrl == null) {
            return CancelResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return CancelResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return CancelResult.failed(400, "apiKey is required");
        }
        if (inviteId.isEmpty()) {
            return CancelResult.failed(400, "inviteId is required");
        }

        String path = HUB_CANCEL_PATH_TEMPLATE.formatted(inviteId);
        String endpoint = hubBaseUrl + path;

        try {
            SignedRequest signed = HubRequestSigner.signRequest("POST", path, "", siteId, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            if (isRedirect(statusCode)) {
                return CancelResult.failed(statusCode, buildRedirectMessage(response, "cancel failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return CancelResult.failed(statusCode, extractMessage(json, "cancel failed"));
            }

            FriendInvitationItem invitation = toInvitationItem(json.path("invitation"));
            return new CancelResult(true, statusCode, extractMessage(json, "ok"), invitation);
        } catch (Exception error) {
            log.warn("[AstraHub] cancel friend invitation failed", error);
            return CancelResult.failed(500, "cancel failed: " + error.getMessage());
        }
    }

    private DeleteResult deleteBlocking(
        DeleteCommand command,
        JsonNode connection,
        JsonNode credentials
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        String inviteId = trim(command.inviteId());

        if (hubBaseUrl == null) {
            return DeleteResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return DeleteResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return DeleteResult.failed(400, "apiKey is required");
        }
        if (inviteId.isEmpty()) {
            return DeleteResult.failed(400, "inviteId is required");
        }

        String path = HUB_DELETE_PATH_TEMPLATE.formatted(inviteId);
        String endpoint = hubBaseUrl + path;

        try {
            SignedRequest signed = HubRequestSigner.signRequest("POST", path, "", siteId, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            if (isRedirect(statusCode)) {
                return DeleteResult.failed(statusCode, buildRedirectMessage(response, "delete failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return DeleteResult.failed(statusCode, extractMessage(json, "delete failed"));
            }

            FriendInvitationItem invitation = toInvitationItem(json.path("invitation"));
            return new DeleteResult(true, statusCode, extractMessage(json, "ok"), invitation);
        } catch (Exception error) {
            log.warn("[AstraHub] delete friend invitation failed", error);
            return DeleteResult.failed(500, "delete failed: " + error.getMessage());
        }
    }

    private RemoveFriendRelationResult removeFriendRelationBlocking(
        RemoveFriendRelationCommand command,
        JsonNode connection,
        JsonNode credentials
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        String peerSiteId = trim(command.peerSiteId());
        String reason = trim(command.reason());

        if (hubBaseUrl == null) {
            return RemoveFriendRelationResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return RemoveFriendRelationResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return RemoveFriendRelationResult.failed(400, "apiKey is required");
        }
        if (peerSiteId.isEmpty()) {
            return RemoveFriendRelationResult.failed(400, "peerSiteId is required");
        }

        String path = HUB_REMOVE_RELATION_PATH_TEMPLATE.formatted(peerSiteId);
        String endpoint = hubBaseUrl + path;

        // 仅当用户填写了 reason 才发送 body；空 body 与有 body 走同样签名规则。
        String body = "";
        if (!reason.isEmpty()) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("reason", reason);
            body = payload.toString();
        }

        try {
            SignedRequest signed = HubRequestSigner.signRequest("POST", path, body, siteId, apiKey);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature());
            if (body.isEmpty()) {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }

            HttpResponse<String> response = HTTP_CLIENT.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            if (isRedirect(statusCode)) {
                return RemoveFriendRelationResult.failed(statusCode, buildRedirectMessage(response, "remove relation failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return RemoveFriendRelationResult.failed(statusCode, extractMessage(json, "remove relation failed"));
            }
            return new RemoveFriendRelationResult(
                true,
                statusCode,
                extractMessage(json, "ok"),
                json.path("removed").asBoolean(false),
                trim(json.path("peerSiteId").asText("")),
                trim(json.path("peerSiteUrl").asText(""))
            );
        } catch (Exception error) {
            log.warn("[AstraHub] remove friend relation failed", error);
            return RemoveFriendRelationResult.failed(500, "remove relation failed: " + error.getMessage());
        }
    }

    private RemoveFriendRelationResult removeOwnFriendFollowBlocking(
        RemoveFriendRelationCommand command,
        JsonNode connection,
        JsonNode credentials
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        String peerSiteId = trim(command.peerSiteId());

        if (hubBaseUrl == null) {
            return RemoveFriendRelationResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return RemoveFriendRelationResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return RemoveFriendRelationResult.failed(400, "apiKey is required");
        }
        if (peerSiteId.isEmpty()) {
            return RemoveFriendRelationResult.failed(400, "peerSiteId is required");
        }

        String path = HUB_REMOVE_FOLLOW_PATH_TEMPLATE.formatted(peerSiteId);
        String endpoint = hubBaseUrl + path;

        try {
            SignedRequest signed = HubRequestSigner.signRequest("POST", path, "", siteId, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            if (isRedirect(statusCode)) {
                return RemoveFriendRelationResult.failed(statusCode, buildRedirectMessage(response, "remove follow failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return RemoveFriendRelationResult.failed(statusCode, extractMessage(json, "remove follow failed"));
            }
            return new RemoveFriendRelationResult(
                true,
                statusCode,
                extractMessage(json, "ok"),
                json.path("removed").asBoolean(false),
                trim(json.path("peerSiteId").asText("")),
                trim(json.path("peerSiteUrl").asText(""))
            );
        } catch (Exception error) {
            log.warn("[AstraHub] remove own friend follow failed", error);
            return RemoveFriendRelationResult.failed(500, "remove follow failed: " + error.getMessage());
        }
    }

    private SiteLookupResult lookupSiteByUrlBlocking(String rawUrl, JsonNode connection, JsonNode credentials) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String targetUrl = trim(rawUrl);
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");

        if (hubBaseUrl == null) {
            return SiteLookupResult.failed(400, "hubBaseUrl is invalid");
        }
        if (targetUrl.isEmpty()) {
            return SiteLookupResult.failed(400, "url is required");
        }
        if (siteId.isEmpty()) {
            return SiteLookupResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return SiteLookupResult.failed(400, "apiKey is required");
        }

        try {
            String endpoint = hubBaseUrl + HUB_SITE_LOOKUP_PATH
                + "?url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8);
            SignedRequest signed = HubRequestSigner.signRequest("GET", HUB_SITE_LOOKUP_PATH, "", siteId, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
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
            if (isRedirect(statusCode)) {
                return SiteLookupResult.failed(statusCode, buildRedirectMessage(response, "lookup failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return SiteLookupResult.failed(statusCode, extractMessage(json, "lookup failed"));
            }

            return new SiteLookupResult(
                true,
                statusCode,
                extractMessage(json, "ok"),
                json.path("registered").asBoolean(false),
                json.path("registeredByPlugin").asBoolean(json.path("registered").asBoolean(false)),
                json.path("credentialReady").asBoolean(false),
                text(json, "siteId"),
                text(json, "siteName"),
                text(json, "siteUrl"),
                text(json, "avatarUrl"),
                json.path("supportsInvitation").asBoolean(false),
                text(json, "invitationState"),
                text(json, "invitationMessage")
            );
        } catch (Exception error) {
            log.warn("[AstraHub] lookup site by url failed", error);
            return SiteLookupResult.failed(500, "lookup failed: " + error.getMessage());
        }
    }

    private SiteRelationBatchResult resolveSiteRelationsBlocking(
        List<String> targetUrls,
        JsonNode connection,
        JsonNode credentials
    ) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");

        if (hubBaseUrl == null) {
            return SiteRelationBatchResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return SiteRelationBatchResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return SiteRelationBatchResult.failed(400, "apiKey is required");
        }

        List<String> urls = new ArrayList<>();
        for (String targetUrl : targetUrls == null ? List.<String>of() : targetUrls) {
            String value = trim(targetUrl);
            if (!value.isEmpty()) {
                urls.add(value);
            }
        }

        try {
            JsonNode bodyNode = MAPPER.createObjectNode()
                .set("targetUrls", MAPPER.valueToTree(urls));
            String body = MAPPER.writeValueAsString(bodyNode);
            SignedRequest signed = HubRequestSigner.signRequest("POST", HUB_SITE_RELATION_BATCH_PATH, body, siteId, apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(hubBaseUrl + HUB_SITE_RELATION_BATCH_PATH))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            if (isRedirect(statusCode)) {
                return SiteRelationBatchResult.failed(statusCode, buildRedirectMessage(response, "relation lookup failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return SiteRelationBatchResult.failed(statusCode, extractMessage(json, "relation lookup failed"));
            }

            List<SiteRelationItem> items = new ArrayList<>();
            JsonNode array = json.path("items");
            if (array.isArray()) {
                for (JsonNode node : array) {
                    items.add(toSiteRelationItem(node));
                }
            }

            return new SiteRelationBatchResult(true, statusCode, extractMessage(json, "ok"), items);
        } catch (Exception error) {
            log.warn("[AstraHub] resolve site relations failed", error);
            return SiteRelationBatchResult.failed(500, "relation lookup failed: " + error.getMessage());
        }
    }

    private RealtimeTokenResult issueRealtimeTokenBlocking(JsonNode connection, JsonNode credentials) {
        String hubBaseUrl = normalizeBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");

        if (hubBaseUrl == null) {
            return RealtimeTokenResult.failed(400, "hubBaseUrl is invalid");
        }
        if (siteId.isEmpty()) {
            return RealtimeTokenResult.failed(400, "siteId is required");
        }
        if (apiKey.isEmpty()) {
            return RealtimeTokenResult.failed(400, "apiKey is required");
        }

        try {
            SignedRequest signed = HubRequestSigner.signRequest("POST", HUB_WS_TOKEN_PATH, "", siteId, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(hubBaseUrl + HUB_WS_TOKEN_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("X-BP-Site-Id", signed.siteId())
                .header("X-BP-Timestamp", signed.timestamp())
                .header("X-BP-Nonce", signed.nonce())
                .header("X-BP-Signature", signed.signature())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int statusCode = response.statusCode();
            if (isRedirect(statusCode)) {
                return RealtimeTokenResult.failed(statusCode, buildRedirectMessage(response, "issue websocket token failed"));
            }
            JsonNode json = parseJson(response.body());
            if (statusCode < 200 || statusCode >= 300) {
                return RealtimeTokenResult.failed(statusCode, extractMessage(json, "issue websocket token failed"));
            }

            return new RealtimeTokenResult(
                true,
                statusCode,
                extractMessage(json, "ok"),
                text(json, "token"),
                text(json, "expiresAt")
            );
        } catch (Exception error) {
            log.warn("[AstraHub] issue realtime token failed", error);
            return RealtimeTokenResult.failed(500, "issue websocket token failed: " + error.getMessage());
        }
    }

    private static FriendInvitationItem toInvitationItem(JsonNode node) {
        return new FriendInvitationItem(
            text(node, "inviteId"),
            toSiteInfo(node.path("fromSite")),
            toSiteInfo(node.path("toSite")),
            text(node, "message"),
            text(node, "status"),
            text(node, "deliveryStatus"),
            text(node, "reviewReason"),
            text(node, "linkGroupName"),
            text(node, "createdAt"),
            text(node, "reviewedAt"),
            text(node, "ackedAt"),
            text(node, "lastError"),
            node.path("retryCount").asInt(0),
            text(node, "updatedAt")
        );
    }

    private static FriendInvitationSiteInfo toSiteInfo(JsonNode node) {
        return new FriendInvitationSiteInfo(
            text(node, "siteId"),
            text(node, "siteName"),
            text(node, "siteUrl"),
            text(node, "description"),
            text(node, "avatarUrl"),
            text(node, "rssUrl"),
            text(node, "contactEmail")
        );
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

    private static JsonNode parseJson(String body) {
        String value = body == null ? "" : body.trim();
        if (value.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(value);
        } catch (Exception ignored) {
            return MAPPER.createObjectNode();
        }
    }

    private static String extractMessage(JsonNode node, String fallback) {
        String nestedDetail = text(node.path("error"), "message");
        if (!nestedDetail.isBlank()) {
            return nestedDetail;
        }
        String nestedCode = text(node.path("error"), "code");
        if (!nestedCode.isBlank() && !fallback.isBlank()) {
            return fallback + ": " + nestedCode;
        }
        String detail = text(node, "detail");
        if (!detail.isBlank()) {
            return detail;
        }
        String message = text(node, "message");
        if (!message.isBlank()) {
            return message;
        }
        String error = text(node, "error");
        if (!error.isBlank()) {
            return error;
        }
        return fallback;
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static String buildRedirectMessage(HttpResponse<?> response, String fallback) {
        String location = response.headers().firstValue("Location").orElse("").trim();
        if (!location.isEmpty()) {
            return "Hub 地址发生跳转，请将 Hub 地址配置为最终地址: " + location;
        }
        return fallback + ": hub redirected";
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

    private static String normalizeBox(String raw) {
        return "outbox".equalsIgnoreCase(trim(raw)) ? "outbox" : "inbox";
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

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : trim(value.asText(""));
    }

    private static SiteRelationItem toSiteRelationItem(JsonNode node) {
        return new SiteRelationItem(
            text(node, "observerSiteId"),
            text(node, "observerSiteUrl"),
            text(node, "targetUrl"),
            toSiteRelationIdentity(node.path("targetIdentity")),
            toSiteRelationSide(node.path("mine")),
            toSiteRelationSide(node.path("theirs")),
            text(node, "relationKind")
        );
    }

    private static SiteRelationIdentity toSiteRelationIdentity(JsonNode node) {
        return new SiteRelationIdentity(
            text(node, "queryUrl"),
            text(node, "normalizedUrl"),
            text(node, "normalizedRootUrl"),
            node.path("registered").asBoolean(false),
            node.path("registeredByPlugin").asBoolean(node.path("registered").asBoolean(false)),
            node.path("credentialReady").asBoolean(false),
            text(node, "siteId"),
            text(node, "siteName"),
            text(node, "siteUrl"),
            text(node, "description"),
            text(node, "rssUrl"),
            text(node, "avatarUrl"),
            text(node, "contactEmail"),
            text(node, "nodeName"),
            text(node, "category"),
            text(node, "nodeAvatar"),
            text(node, "status"),
            node.path("supportsInvitation").asBoolean(false),
            text(node, "invitationState"),
            text(node, "invitationMessage"),
            text(node, "matchedBy"),
            text(node, "lookupError")
        );
    }

    private static SiteRelationSide toSiteRelationSide(JsonNode node) {
        return new SiteRelationSide(
            node.path("known").asBoolean(false),
            node.path("added").asBoolean(false),
            text(node, "snapshotAt"),
            text(node, "updatedAt"),
            text(node, "reason")
        );
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record FriendInvitationsQuery(String box, String status) {
    }

    public record ReviewCommand(
        String inviteId,
        boolean approved,
        String reason,
        String linkGroupName
    ) {
    }

    public record CreateCommand(
        String toSiteId,
        String message,
        String linkGroupName
    ) {
    }

    public record AckCommand(
        String inviteId,
        String lastError
    ) {
    }

    public record CancelCommand(
        String inviteId
    ) {
    }

    public record DeleteCommand(
        String inviteId
    ) {
    }

    public record RemoveFriendRelationCommand(
        String peerSiteId,
        String reason
    ) {
    }

    public record FriendInvitationSiteInfo(
        String siteId,
        String siteName,
        String siteUrl,
        String description,
        String avatarUrl,
        String rssUrl,
        String contactEmail
    ) {
    }

    public record FriendInvitationItem(
        String inviteId,
        FriendInvitationSiteInfo fromSite,
        FriendInvitationSiteInfo toSite,
        String message,
        String status,
        String deliveryStatus,
        String reviewReason,
        String linkGroupName,
        String createdAt,
        String reviewedAt,
        String ackedAt,
        String lastError,
        int retryCount,
        String updatedAt
    ) {
    }

    public record FriendInvitationsQueryResult(
        boolean success,
        int status,
        String message,
        String box,
        String generatedAt,
        int total,
        List<FriendInvitationItem> items
    ) {
        public static FriendInvitationsQueryResult failed(int status, String message) {
            return new FriendInvitationsQueryResult(false, status, message, "", "", 0, List.of());
        }
    }

    public record ReviewResult(
        boolean success,
        int status,
        String message,
        FriendInvitationItem invitation
    ) {
        public static ReviewResult failed(int status, String message) {
            return new ReviewResult(false, status, message, null);
        }
    }

    public record AckResult(
        boolean success,
        int status,
        String message,
        FriendInvitationItem invitation
    ) {
        public static AckResult failed(int status, String message) {
            return new AckResult(false, status, message, null);
        }
    }

    public record CancelResult(
        boolean success,
        int status,
        String message,
        FriendInvitationItem invitation
    ) {
        public static CancelResult failed(int status, String message) {
            return new CancelResult(false, status, message, null);
        }
    }

    public record DeleteResult(
        boolean success,
        int status,
        String message,
        FriendInvitationItem invitation
    ) {
        public static DeleteResult failed(int status, String message) {
            return new DeleteResult(false, status, message, null);
        }
    }

    public record RemoveFriendRelationResult(
        boolean success,
        int status,
        String message,
        boolean removed,
        String peerSiteId,
        String peerSiteUrl
    ) {
        public static RemoveFriendRelationResult failed(int status, String message) {
            return new RemoveFriendRelationResult(false, status, message, false, "", "");
        }
    }

    public record CreateResult(
        boolean success,
        int status,
        String message,
        FriendInvitationItem invitation
    ) {
        public static CreateResult failed(int status, String message) {
            return new CreateResult(false, status, message, null);
        }
    }

    public record LinkGroupOption(
        String name,
        String displayName
    ) {
    }

    public record SiteLookupResult(
        boolean success,
        int status,
        String message,
        boolean registered,
        boolean registeredByPlugin,
        boolean credentialReady,
        String siteId,
        String siteName,
        String siteUrl,
        String avatarUrl,
        boolean supportsInvitation,
        String invitationState,
        String invitationMessage
    ) {
        public static SiteLookupResult failed(int status, String message) {
            return new SiteLookupResult(false, status, message, false, false, false, "", "", "", "", false, "", "");
        }
    }

    public record SiteRelationIdentity(
        String queryUrl,
        String normalizedUrl,
        String normalizedRootUrl,
        boolean registered,
        boolean registeredByPlugin,
        boolean credentialReady,
        String siteId,
        String siteName,
        String siteUrl,
        String description,
        String rssUrl,
        String avatarUrl,
        String contactEmail,
        String nodeName,
        String category,
        String nodeAvatar,
        String siteStatus,
        boolean supportsInvitation,
        String invitationState,
        String invitationMessage,
        String matchedBy,
        String lookupError
    ) {
    }

    public record SiteRelationSide(
        boolean known,
        boolean added,
        String snapshotAt,
        String updatedAt,
        String reason
    ) {
    }

    public record SiteRelationItem(
        String observerSiteId,
        String observerSiteUrl,
        String targetUrl,
        SiteRelationIdentity targetIdentity,
        SiteRelationSide mine,
        SiteRelationSide theirs,
        String relationKind
    ) {
    }

    public record SiteRelationBatchResult(
        boolean success,
        int status,
        String message,
        List<SiteRelationItem> items
    ) {
        public static SiteRelationBatchResult failed(int status, String message) {
            return new SiteRelationBatchResult(false, status, message, List.of());
        }
    }

    public record RealtimeTokenResult(
        boolean success,
        int status,
        String message,
        String token,
        String expiresAt
    ) {
        public static RealtimeTokenResult failed(int status, String message) {
            return new RealtimeTokenResult(false, status, message, "", "");
        }
    }

    public record LinkGroupOptionsResult(
        boolean success,
        int status,
        String message,
        List<LinkGroupOption> items
    ) {
        public static LinkGroupOptionsResult failed(int status, String message) {
            return new LinkGroupOptionsResult(false, status, message, List.of());
        }
    }
}
