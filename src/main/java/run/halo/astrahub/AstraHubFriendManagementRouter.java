package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.astrahub.model.FriendInvitation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class AstraHubFriendManagementRouter implements CustomEndpoint {

    private final AstraHubFriendManagementService friendManagementService;
    private final AstraHubFriendLinkReconcileService friendLinkReconcileService;
    private final AstraHubFriendInboxSyncService friendInboxSyncService;
    private final AstraHubFriendOutboxSyncService friendOutboxSyncService;
    private final AstraHubFriendRetryService friendRetryService;
    private final AstraHubLinkChangeMonitorService linkChangeMonitorService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .GET("astrahub/sites/lookup", this::lookupSiteByUrl,
                builder -> builder.operationId("LookupAstraHubSiteByUrl")
                    .tag(tag)
                    .description("Lookup whether a site URL is already registered in AstraHub Hub")
                    .response(responseBuilder().description("Lookup result")))
            .POST("astrahub/site-relations/batch", this::batchResolveSiteRelations,
                builder -> builder.operationId("BatchResolveAstraHubSiteRelations")
                    .tag(tag)
                    .description("Resolve site relations in batch from AstraHub Hub")
                    .response(responseBuilder().description("Batch relation result")))
            .POST("astrahub/ws-token", this::issueRealtimeToken,
                builder -> builder.operationId("IssueAstraHubRealtimeToken")
                    .tag(tag)
                    .description("Issue short-lived websocket token from AstraHub Hub")
                    .response(responseBuilder().description("Realtime token result")))
            .GET("astrahub/friend-invitations", this::listInvitations,
                builder -> builder.operationId("ListAstraHubFriendInvitations")
                    .tag(tag)
                    .description("List inbox or outbox friend invitations from AstraHub Hub")
                    .response(responseBuilder().description("Friend invitation list")))
            .POST("astrahub/friend-invitations", this::createInvitation,
                builder -> builder.operationId("CreateAstraHubFriendInvitation")
                    .tag(tag)
                    .description("Create friend invitation to AstraHub Hub and cache local outbox")
                    .response(responseBuilder().description("Create result")))
            .POST("astrahub/friend-invitations/inbox/sync", this::syncInbox,
                builder -> builder.operationId("SyncAstraHubFriendInvitationInbox")
                    .tag(tag)
                    .description("Sync inbox friend invitations from AstraHub Hub into local cache")
                    .response(responseBuilder().description("Inbox sync result")))
            .POST("astrahub/friend-invitations/outbox/sync", this::syncOutbox,
                builder -> builder.operationId("SyncAstraHubFriendInvitationOutbox")
                    .tag(tag)
                    .description("Sync outbox friend invitations from AstraHub Hub into local cache")
                    .response(responseBuilder().description("Outbox sync result")))
            .GET("astrahub/friend-invitations/link-groups", this::listLinkGroups,
                builder -> builder.operationId("ListAstraHubFriendInvitationLinkGroups")
                    .tag(tag)
                    .description("List local Halo link groups for invitation review")
                    .response(responseBuilder().description("Link group options")))
            .POST("astrahub/friend-invitations/{inviteId}/review", this::reviewInvitation,
                builder -> builder.operationId("ReviewAstraHubFriendInvitation")
                    .tag(tag)
                    .description("Review a pending friend invitation through AstraHub Hub")
                    .response(responseBuilder().description("Review result")))
            .POST("astrahub/friend-invitations/{inviteId}/cancel", this::cancelInvitation,
                builder -> builder.operationId("CancelAstraHubFriendInvitation")
                    .tag(tag)
                    .description("Cancel a pending outbox friend invitation through AstraHub Hub")
                    .response(responseBuilder().description("Cancel result")))
            .POST("astrahub/friend-invitations/{inviteId}/reconcile", this::reconcileInvitation,
                builder -> builder.operationId("ReconcileAstraHubFriendInvitation")
                    .tag(tag)
                    .description("Create local Halo link from an accepted friend invitation")
                    .response(responseBuilder().description("Reconcile result")))
            .POST("astrahub/friend-invitations/{inviteId}/ack", this::ackInvitation,
                builder -> builder.operationId("AckAstraHubFriendInvitation")
                    .tag(tag)
                    .description("Ack accepted invitation result back to AstraHub Hub")
                    .response(responseBuilder().description("Ack result")))
            .POST("astrahub/friend-invitations/{inviteId}/retry", this::retryInvitation,
                builder -> builder.operationId("RetryAstraHubFriendInvitation")
                    .tag(tag)
                    .description("Retry local reconcile and ack for an accepted outbox invitation")
                    .response(responseBuilder().description("Retry result")))
            .POST("astrahub/friend-invitations/{inviteId}/delete", this::deleteInvitation,
                builder -> builder.operationId("DeleteAstraHubFriendInvitation")
                    .tag(tag)
                    .description("Delete local invitation cache, and cancel remote pending outbox invitation first when needed")
                    .response(responseBuilder().description("Delete result")))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> listInvitations(ServerRequest request) {
        String box = request.queryParam("box").orElse("inbox");
        String status = request.queryParam("status").orElse("");
        String source = request.queryParam("source").orElse("");
        if ("remote".equalsIgnoreCase(source)) {
            return friendManagementService.list(new AstraHubFriendManagementService.FriendInvitationsQuery(box, status))
                .flatMap(result -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("success", result.success());
                    body.put("status", result.status());
                    body.put("message", result.message());
                    body.put("box", result.box());
                    body.put("generatedAt", result.generatedAt());
                    body.put("total", result.total());
                    body.put("items", result.items());
                    if (result.success()) {
                        return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body);
                    }
                    int statusCode = result.status() >= 400 ? result.status() : 400;
                    return ServerResponse.status(statusCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body);
                });
        }
        if ("inbox".equalsIgnoreCase(box)) {
            return friendInboxSyncService.listLocalInbox(status)
                .flatMap(result -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("success", result.success());
                    body.put("status", result.success() ? 200 : 500);
                    body.put("message", result.success() ? "ok" : "load local inbox failed");
                    body.put("box", "inbox");
                    body.put("generatedAt", java.time.Instant.now().toString());
                    body.put("total", result.total());
                    body.put("items", result.items().stream().map(FriendInvitationView::fromLocal).toList());
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body);
                });
        }
        return friendOutboxSyncService.listLocalOutbox(status)
            .flatMap(result -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", result.success());
                body.put("status", result.success() ? 200 : 500);
                body.put("message", result.success() ? "ok" : "load local outbox failed");
                body.put("box", "outbox");
                body.put("generatedAt", java.time.Instant.now().toString());
                body.put("total", result.total());
                body.put("items", result.items().stream().map(FriendInvitationView::fromLocal).toList());
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body);
            });
    }

    private Mono<ServerResponse> lookupSiteByUrl(ServerRequest request) {
        String rawUrl = request.queryParam("url").orElse("");
        return friendManagementService.lookupSiteByUrl(rawUrl)
            .flatMap(result -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", result.success());
                body.put("status", result.status());
                body.put("message", result.message());
                body.put("registered", result.registered());
                body.put("registeredByPlugin", result.registeredByPlugin());
                body.put("credentialReady", result.credentialReady());
                body.put("siteId", result.siteId());
                body.put("siteName", result.siteName());
                body.put("siteUrl", result.siteUrl());
                body.put("avatarUrl", result.avatarUrl());
                body.put("supportsInvitation", result.supportsInvitation());
                body.put("invitationState", result.invitationState());
                body.put("invitationMessage", result.invitationMessage());
                if (result.success()) {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
                }
                int statusCode = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(statusCode).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] lookup site by url error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "status", 500, "message", "internal error: " + error.getMessage()));
            });
    }

    private Mono<ServerResponse> createInvitation(ServerRequest request) {
        return request.bodyToMono(CreateRequest.class)
            .defaultIfEmpty(new CreateRequest("", "", ""))
            .flatMap(body -> friendManagementService.create(new AstraHubFriendManagementService.CreateCommand(
                body.toSiteId(),
                body.message(),
                body.linkGroupName()
            )))
            .flatMap(result -> {
                if (!result.success()) {
                    int statusCode = result.status() >= 400 ? result.status() : 400;
                    return ServerResponse.status(statusCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                            "success", false,
                            "status", result.status(),
                            "message", result.message()
                        ));
                }
                return friendOutboxSyncService.storeCreatedOutbox(result.invitation())
                    .flatMap(store -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                            "success", true,
                            "status", result.status(),
                            "message", result.message(),
                            "invitation", result.invitation(),
                            "stored", store.success()
                        )));
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] create friend invitation error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "status", 500, "message", "internal error: " + error.getMessage()));
            });
    }

    private Mono<ServerResponse> issueRealtimeToken(ServerRequest request) {
        return friendManagementService.issueRealtimeToken()
            .flatMap(result -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", result.success());
                body.put("status", result.status());
                body.put("message", result.message());
                body.put("token", result.token());
                body.put("expiresAt", result.expiresAt());
                if (result.success()) {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
                }
                int statusCode = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(statusCode).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] issue realtime token error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "status", 500, "message", "internal error: " + error.getMessage()));
            });
    }

    private Mono<ServerResponse> batchResolveSiteRelations(ServerRequest request) {
        return request.bodyToMono(SiteRelationBatchRequest.class)
            .defaultIfEmpty(new SiteRelationBatchRequest(java.util.List.of()))
            .flatMap(body -> friendManagementService.resolveSiteRelations(body.targetUrls()))
            .flatMap(result -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", result.success());
                body.put("status", result.status());
                body.put("message", result.message());
                body.put("items", result.items());
                if (result.success()) {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
                }
                int statusCode = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(statusCode).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] batch resolve site relations error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "status", 500, "message", "internal error: " + error.getMessage(), "items", java.util.List.of()));
            });
    }

    private Mono<ServerResponse> syncInbox(ServerRequest request) {
        return friendInboxSyncService.syncNow()
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "success", result.success(),
                    "total", result.total(),
                    "changed", result.changed(),
                    "message", result.message()
                )))
            .onErrorResume(error -> {
                log.error("[AstraHub] sync local inbox error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "message", "internal error: " + error.getMessage()));
            });
    }

    private Mono<ServerResponse> syncOutbox(ServerRequest request) {
        return friendOutboxSyncService.syncNow()
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "success", result.success(),
                    "total", result.total(),
                    "changed", result.changed(),
                    "message", result.message()
                )))
            .onErrorResume(error -> {
                log.error("[AstraHub] sync local outbox error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "message", "internal error: " + error.getMessage()));
            });
    }

    private Mono<ServerResponse> deleteInvitation(ServerRequest request) {
        String inviteId = request.pathVariable("inviteId");
        return friendManagementService.delete(new AstraHubFriendManagementService.DeleteCommand(inviteId))
            .flatMap(result -> {
                if (!result.success()) {
                    int statusCode = result.status() >= 400 ? result.status() : 400;
                    return Mono.error(new DeleteInvitationException(statusCode, result.message()));
                }
                return deleteLocalInvitations(inviteId)
                    .then(ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("success", true, "status", 200, "message", "ok")));
            })
            .onErrorResume(DeleteInvitationException.class, error -> ServerResponse.status(error.status())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("success", false, "status", error.status(), "message", error.getMessage())))
            .onErrorResume(error -> {
                log.error("[AstraHub] delete invitation error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "status", 500, "message", "internal error: " + error.getMessage()));
            });
    }

    private Mono<Void> deleteLocalInvitations(String inviteId) {
        return friendInboxSyncService.listLocalInbox("")
            .zipWith(friendOutboxSyncService.listLocalOutbox(""))
            .flatMap(tuple -> {
                Mono<Void> chain = Mono.empty();
                for (FriendInvitation item : tuple.getT1().items()) {
                    if (matchesInviteId(item, inviteId)) {
                        chain = chain.then(deleteLocalInvitation(item));
                    }
                }
                for (FriendInvitation item : tuple.getT2().items()) {
                    if (matchesInviteId(item, inviteId)) {
                        chain = chain.then(deleteLocalInvitation(item));
                    }
                }
                return chain;
            });
    }

    private static boolean matchesInviteId(FriendInvitation item, String inviteId) {
        if (item == null || item.getSpec() == null) {
            return false;
        }
        return Objects.equals(
            trim(item.getSpec().getInvitationId()),
            trim(inviteId)
        );
    }

    private Mono<Void> deleteLocalInvitation(FriendInvitation invitation) {
        String name = trim(invitation.getMetadata() == null ? null : invitation.getMetadata().getName());
        if (name.isEmpty()) {
            return Mono.error(new IllegalStateException("local invitation metadata.name is empty"));
        }
        return friendManagementService.deleteLocalInvitation(name);
    }

    private Mono<ServerResponse> listLinkGroups(ServerRequest request) {
        return friendManagementService.listLinkGroups()
            .flatMap(result -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", result.success());
                body.put("status", result.status());
                body.put("message", result.message());
                body.put("items", result.items());
                if (result.success()) {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
                }
                int statusCode = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(statusCode).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] list friend invitation link groups error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "status", 500, "message", "internal error: " + error.getMessage(), "items", java.util.List.of()));
            });
    }

    private Mono<ServerResponse> reviewInvitation(ServerRequest request) {
        String inviteId = request.pathVariable("inviteId");
        return request.bodyToMono(ReviewRequest.class)
            .defaultIfEmpty(new ReviewRequest(false, "", ""))
            .flatMap(body -> friendManagementService.review(new AstraHubFriendManagementService.ReviewCommand(
                inviteId,
                body.approved(),
                body.reason(),
                body.linkGroupName()
            )))
            .flatMap(result -> {
                Map<String, Object> responseBody = new LinkedHashMap<>();
                responseBody.put("success", result.success());
                responseBody.put("status", result.status());
                responseBody.put("message", result.message());
                responseBody.put("invitation", result.invitation());
                if (result.success()) {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(responseBody);
                }
                int statusCode = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(statusCode).contentType(MediaType.APPLICATION_JSON).bodyValue(responseBody);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] review friend invitation error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "status", 500, "message", "internal error: " + error.getMessage()));
            });
    }

    private Mono<ServerResponse> cancelInvitation(ServerRequest request) {
        String inviteId = request.pathVariable("inviteId");
        return friendManagementService.cancel(new AstraHubFriendManagementService.CancelCommand(inviteId))
            .flatMap(result -> {
                Map<String, Object> responseBody = new LinkedHashMap<>();
                responseBody.put("success", result.success());
                responseBody.put("status", result.status());
                responseBody.put("message", result.message());
                responseBody.put("invitation", result.invitation());
                if (result.success()) {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(responseBody);
                }
                int statusCode = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(statusCode).contentType(MediaType.APPLICATION_JSON).bodyValue(responseBody);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] cancel friend invitation error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "status", 500, "message", "internal error: " + error.getMessage()));
            });
    }

    public record ReviewRequest(
        boolean approved,
        String reason,
        String linkGroupName
    ) {
    }

    public record CreateRequest(
        String toSiteId,
        String message,
        String linkGroupName
    ) {
    }

    public record SiteRelationBatchRequest(
        java.util.List<String> targetUrls
    ) {
    }

    private Mono<ServerResponse> reconcileInvitation(ServerRequest request) {
        String inviteId = request.pathVariable("inviteId");
        return request.bodyToMono(ReconcileRequest.class)
            .flatMap(body -> friendLinkReconcileService.reconcile(
                new AstraHubFriendManagementService.FriendInvitationItem(
                    inviteId,
                    new AstraHubFriendManagementService.FriendInvitationSiteInfo(
                        body.fromSiteId(), body.fromSiteName(), body.fromSiteUrl(),
                        body.fromDescription(), body.fromAvatarUrl(), body.fromRssUrl(), body.fromContactEmail()
                    ),
                    new AstraHubFriendManagementService.FriendInvitationSiteInfo(
                        body.toSiteId(), body.toSiteName(), body.toSiteUrl(),
                        body.toDescription(), body.toAvatarUrl(), body.toRssUrl(), body.toContactEmail()
                    ),
                    body.message(),
                    body.status(),
                    body.deliveryStatus(),
                    body.reviewReason(),
                    body.linkGroupName(),
                    body.createdAt(),
                    body.reviewedAt(),
                    body.ackedAt(),
                    body.lastError(),
                    body.retryCount(),
                    body.updatedAt()
                ),
                body.currentSiteId()
            ))
            .flatMap(result -> {
                if (!result.success()) {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "success", false,
                        "created", result.created(),
                        "duplicate", result.duplicate(),
                        "message", result.message(),
                        "inviteId", result.inviteId(),
                        "peerSiteId", result.peerSiteId(),
                        "peerSiteUrl", result.peerSiteUrl(),
                        "linkName", result.linkName(),
                        "linkEdgePushSuccess", false,
                        "linkEdgePushMessage", ""
                    ));
                }
                return linkChangeMonitorService.observeNow("friend_reconcile")
                    .onErrorResume(error -> Mono.just(AstraHubLinkChangeMonitorService.ObserveResult.failed(
                        "friend_reconcile",
                        "observe failed: " + error.getMessage()
                    )))
                    .flatMap(observeResult -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "success", true,
                        "created", result.created(),
                        "duplicate", result.duplicate(),
                        "message", result.message(),
                        "inviteId", result.inviteId(),
                        "peerSiteId", result.peerSiteId(),
                        "peerSiteUrl", result.peerSiteUrl(),
                        "linkName", result.linkName(),
                        "linkEdgePushSuccess", observeResult.success(),
                        "linkEdgePushMessage", observeResult.message()
                    )));
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] reconcile friend invitation error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "message", "internal error: " + error.getMessage()));
            });
    }

    private Mono<ServerResponse> ackInvitation(ServerRequest request) {
        String inviteId = request.pathVariable("inviteId");
        return request.bodyToMono(AckRequest.class)
            .defaultIfEmpty(new AckRequest(""))
            .flatMap(body -> friendManagementService.ack(new AstraHubFriendManagementService.AckCommand(
                inviteId,
                body.lastError()
            )))
            .flatMap(result -> {
                Map<String, Object> responseBody = new LinkedHashMap<>();
                responseBody.put("success", result.success());
                responseBody.put("status", result.status());
                responseBody.put("message", result.message());
                responseBody.put("invitation", result.invitation());
                if (result.success()) {
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(responseBody);
                }
                int statusCode = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(statusCode).contentType(MediaType.APPLICATION_JSON).bodyValue(responseBody);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] ack friend invitation error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "status", 500, "message", "internal error: " + error.getMessage()));
            });
    }

    private Mono<ServerResponse> retryInvitation(ServerRequest request) {
        String inviteId = request.pathVariable("inviteId");
        return friendRetryService.retryInvitation(inviteId)
            .flatMap(result -> {
                if (result.success()) {
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                            "success", true,
                            "inviteId", inviteId,
                            "message", result.message()
                        ));
                }
                return ServerResponse.status(409)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "inviteId", inviteId,
                        "message", result.message()
                    ));
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] retry friend invitation error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("success", false, "message", "internal error: " + error.getMessage()));
            });
    }

    public record AckRequest(
        String lastError
    ) {
    }

    public record ReconcileRequest(
        String currentSiteId,
        String fromSiteId,
        String fromSiteName,
        String fromSiteUrl,
        String fromDescription,
        String fromAvatarUrl,
        String fromRssUrl,
        String fromContactEmail,
        String toSiteId,
        String toSiteName,
        String toSiteUrl,
        String toDescription,
        String toAvatarUrl,
        String toRssUrl,
        String toContactEmail,
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

    private record FriendInvitationView(
        String inviteId,
        AstraHubFriendManagementService.FriendInvitationSiteInfo fromSite,
        AstraHubFriendManagementService.FriendInvitationSiteInfo toSite,
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
        static FriendInvitationView fromLocal(run.halo.astrahub.model.FriendInvitation item) {
            var spec = item.getSpec();
            var peer = new AstraHubFriendManagementService.FriendInvitationSiteInfo(
                spec == null ? "" : nullToEmpty(spec.getPeerSiteId()),
                spec == null ? "" : nullToEmpty(spec.getPeerSiteName()),
                spec == null ? "" : nullToEmpty(spec.getPeerSiteUrl()),
                spec == null ? "" : nullToEmpty(spec.getPeerSiteDescription()),
                spec == null ? "" : nullToEmpty(spec.getPeerSiteAvatar()),
                spec == null ? "" : nullToEmpty(spec.getPeerSiteRssUrl()),
                spec == null ? "" : nullToEmpty(spec.getPeerContactEmail())
            );
            var self = new AstraHubFriendManagementService.FriendInvitationSiteInfo("", "", "", "", "", "", "");
            var from = spec != null && spec.getDirection() == run.halo.astrahub.model.FriendInvitation.InvitationDirection.outbox ? self : peer;
            var to = spec != null && spec.getDirection() == run.halo.astrahub.model.FriendInvitation.InvitationDirection.outbox ? peer : self;
            return new FriendInvitationView(
                spec == null ? "" : nullToEmpty(spec.getInvitationId()),
                from,
                to,
                spec == null ? "" : nullToEmpty(spec.getMessage()),
                spec == null || spec.getStatus() == null ? "pending" : spec.getStatus().name(),
                spec == null ? "" : nullToEmpty(spec.getDeliveryStatus()),
                spec == null ? "" : nullToEmpty(spec.getReviewReason()),
                spec == null ? "" : nullToEmpty(spec.getLinkGroupName()),
                spec == null ? "" : nullToEmpty(spec.getCreatedAt()),
                spec == null ? "" : nullToEmpty(spec.getReviewedAt()),
                spec == null ? "" : nullToEmpty(spec.getAckedAt()),
                spec == null ? "" : nullToEmpty(spec.getLastError()),
                spec == null || spec.getRetryCount() == null ? 0 : spec.getRetryCount(),
                spec == null ? "" : nullToEmpty(spec.getUpdatedAt())
            );
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class DeleteInvitationException extends RuntimeException {
        private final int status;

        private DeleteInvitationException(int status, String message) {
            super(message == null || message.isBlank() ? "delete failed" : message);
            this.status = status;
        }

        private int status() {
            return status;
        }
    }
}
