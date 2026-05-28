package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.astrahub.model.FriendInvitation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubFriendRetryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(2);

    private final ReactiveSettingFetcher settingFetcher;
    private final AstraHubFriendOutboxSyncService friendOutboxSyncService;
    private final AstraHubFriendLinkReconcileService friendLinkReconcileService;
    private final AstraHubFriendManagementService friendManagementService;

    private final Scheduler scheduler = Schedulers.newSingle("astrahub-friend-retry");
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicReference<Disposable> loopTask = new AtomicReference<>();

    public void start() {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        scheduleNext(Duration.ofSeconds(20));
    }

    public void stop() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        Disposable current = loopTask.getAndSet(null);
        if (current != null && !current.isDisposed()) {
            current.dispose();
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
        scheduler.dispose();
    }

    public Mono<RetryResult> retryInvitation(String inviteId) {
        return readCurrentSiteId()
            .flatMap(siteId -> {
                if (trim(siteId).isEmpty()) {
                    return Mono.just(RetryResult.failed("current site is not registered"));
                }
                return friendOutboxSyncService.findLocalOutbox(inviteId)
                    .flatMap(local -> {
                        if (local == null || local.getSpec() == null) {
                            return Mono.just(RetryResult.failed("local invitation not found"));
                        }
                        return retryLocalInvitation(local, siteId);
                    });
            });
    }

    public Mono<RetryBatchResult> retryPendingAcks() {
        return readCurrentSiteId()
            .flatMap(siteId -> {
                if (trim(siteId).isEmpty()) {
                    return Mono.just(new RetryBatchResult(false, 0, 0, "current site is not registered"));
                }
                return friendOutboxSyncService.listLocalOutbox("")
                    .flatMap(local -> {
                        List<FriendInvitation> items = local.items().stream()
                            .filter(item -> item.getSpec() != null)
                            .filter(item -> item.getSpec().getDirection() == FriendInvitation.InvitationDirection.outbox)
                            .filter(item -> item.getSpec().getStatus() == FriendInvitation.InvitationStatus.accepted)
                            .filter(item -> !"acknowledged".equalsIgnoreCase(trim(item.getSpec().getDeliveryStatus())))
                            .toList();
                        return Flux.fromIterable(items)
                            .concatMap(item -> retryLocalInvitation(item, siteId))
                            .collectList()
                            .map(results -> {
                                long successCount = results.stream().filter(RetryResult::success).count();
                                return new RetryBatchResult(true, results.size(), (int) successCount, "ok");
                            });
                    });
            })
            .onErrorResume(error -> {
                log.warn("[AstraHub] retry pending friend invitations failed", error);
                return Mono.just(new RetryBatchResult(false, 0, 0, error.getMessage()));
            });
    }

    private void scheduleNext(Duration delay) {
        if (!active.get()) {
            return;
        }
        Disposable next = Mono.delay(delay, scheduler)
            .flatMap(ignored -> retryPendingAcks())
            .onErrorResume(error -> {
                log.warn("[AstraHub] friend retry cycle failed", error);
                return Mono.just(new RetryBatchResult(false, 0, 0, error.getMessage()));
            })
            .subscribe(result -> scheduleNext(DEFAULT_INTERVAL));

        Disposable old = loopTask.getAndSet(next);
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
    }

    private Mono<RetryResult> retryLocalInvitation(FriendInvitation local, String currentSiteId) {
        AstraHubFriendManagementService.FriendInvitationItem invitation = toRemoteView(local);
        String inviteId = invitation.inviteId();
        return friendLinkReconcileService.reconcile(invitation, currentSiteId)
            .flatMap(reconcile -> {
                if (!reconcile.success()) {
                    return friendOutboxSyncService.markDeliveryFailure(inviteId, reconcile.message())
                        .thenReturn(RetryResult.failed(reconcile.message()));
                }
                return friendManagementService.ack(new AstraHubFriendManagementService.AckCommand(inviteId, ""))
                    .flatMap(ack -> {
                        if (!ack.success()) {
                            return friendOutboxSyncService.markDeliveryFailure(inviteId, ack.message())
                                .thenReturn(RetryResult.failed(ack.message()));
                        }
                        return friendOutboxSyncService.syncNow()
                            .onErrorResume(error -> Mono.empty())
                            .then(friendOutboxSyncService.clearDeliveryFailure(inviteId))
                            .thenReturn(RetryResult.success(inviteId, reconcile.duplicate() ? "duplicate" : "acknowledged"));
                    });
            })
            .onErrorResume(error -> friendOutboxSyncService.markDeliveryFailure(inviteId, error.getMessage())
                .thenReturn(RetryResult.failed(error.getMessage())));
    }

    private static AstraHubFriendManagementService.FriendInvitationItem toRemoteView(FriendInvitation local) {
        FriendInvitation.FriendInvitationSpec spec = local.getSpec();
        AstraHubFriendManagementService.FriendInvitationSiteInfo self = new AstraHubFriendManagementService.FriendInvitationSiteInfo("", "", "", "", "", "", "");
        AstraHubFriendManagementService.FriendInvitationSiteInfo peer = new AstraHubFriendManagementService.FriendInvitationSiteInfo(
            trim(spec == null ? null : spec.getPeerSiteId()),
            trim(spec == null ? null : spec.getPeerSiteName()),
            trim(spec == null ? null : spec.getPeerSiteUrl()),
            trim(spec == null ? null : spec.getPeerSiteDescription()),
            trim(spec == null ? null : spec.getPeerSiteAvatar()),
            trim(spec == null ? null : spec.getPeerSiteRssUrl()),
            trim(spec == null ? null : spec.getPeerContactEmail())
        );
        return new AstraHubFriendManagementService.FriendInvitationItem(
            trim(spec == null ? null : spec.getInvitationId()),
            self,
            peer,
            trim(spec == null ? null : spec.getMessage()),
            spec == null || spec.getStatus() == null ? "pending" : spec.getStatus().name(),
            trim(spec == null ? null : spec.getDeliveryStatus()),
            trim(spec == null ? null : spec.getReviewReason()),
            trim(spec == null ? null : spec.getLinkGroupName()),
            trim(spec == null ? null : spec.getCreatedAt()),
            trim(spec == null ? null : spec.getReviewedAt()),
            trim(spec == null ? null : spec.getAckedAt()),
            trim(spec == null ? null : spec.getLastError()),
            spec == null || spec.getRetryCount() == null ? 0 : spec.getRetryCount(),
            trim(spec == null ? null : spec.getUpdatedAt())
        );
    }

    private Mono<String> readCurrentSiteId() {
        return settingFetcher.getSettingValue("credentials")
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .map(node -> trim(node.path("siteId").asText("")))
            .onErrorResume(error -> Mono.just(""));
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

    public record RetryResult(
        boolean success,
        String inviteId,
        String message
    ) {
        public static RetryResult success(String inviteId, String message) {
            return new RetryResult(true, inviteId, message);
        }

        public static RetryResult failed(String message) {
            return new RetryResult(false, "", message);
        }
    }

    public record RetryBatchResult(
        boolean success,
        int total,
        int successCount,
        String message
    ) {
    }
}
