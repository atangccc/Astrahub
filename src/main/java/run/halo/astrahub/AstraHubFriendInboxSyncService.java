package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.astrahub.model.FriendInvitation;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubFriendInboxSyncService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    private final ReactiveSettingFetcher settingFetcher;
    private final ReactiveExtensionClient client;
    private final AstraHubFriendManagementService friendManagementService;
    private final AstraHubFriendSettingsService friendSettingsService;
    private final Scheduler scheduler = Schedulers.newSingle("astrahub-friend-inbox-sync");
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicReference<Disposable> loopTask = new AtomicReference<>();

    public void start() {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        scheduleNext(Duration.ZERO);
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

    public Mono<SyncResult> syncNow() {
        return runSyncCycle();
    }

    public Mono<LocalFriendInvitationsResult> listLocalInbox(String status) {
        return client.listAll(FriendInvitation.class, new ListOptions(), Sort.by("metadata.creationTimestamp"))
            .collectList()
            .map(items -> {
                List<FriendInvitation> filtered = items.stream()
                    .filter(item -> item.getSpec() != null)
                    .filter(item -> item.getSpec().getDirection() == FriendInvitation.InvitationDirection.inbox)
                    .filter(item -> status == null || status.isBlank() || item.getSpec().getStatus().name().equalsIgnoreCase(status.trim()))
                    .toList();
                return new LocalFriendInvitationsResult(true, filtered.size(), filtered);
            })
            .onErrorResume(error -> {
                log.warn("[AstraHub] list local inbox failed", error);
                return Mono.just(new LocalFriendInvitationsResult(false, 0, List.of()));
            });
    }

    private void scheduleNext(Duration delay) {
        if (!active.get()) {
            return;
        }
        Disposable next = Mono.delay(delay, scheduler)
            .flatMap(ignored -> runSyncCycle())
            .onErrorResume(error -> {
                log.warn("[AstraHub] friend inbox sync cycle failed", error);
                return Mono.just(new SyncResult(false, 0, 0, error.getMessage()));
            })
            .subscribe(result -> scheduleNext(DEFAULT_INTERVAL));

        Disposable old = loopTask.getAndSet(next);
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
    }

    private Mono<SyncResult> runSyncCycle() {
        if (!syncing.compareAndSet(false, true)) {
            return Mono.just(new SyncResult(false, 0, 0, "sync already running"));
        }
        return Mono.zip(readHubConfigured(), friendSettingsService.readInvitationSettings())
            .flatMap(settingsTuple -> {
                boolean configured = settingsTuple.getT1();
                AstraHubFriendSettingsService.InvitationSettings invitationSettings = settingsTuple.getT2();
                if (!configured) {
                    return Mono.just(new SyncResult(false, 0, 0, "hub not configured"));
                }
                if (!invitationSettings.allowIncomingInvitations()) {
                    return Mono.just(new SyncResult(false, 0, 0, "当前站点已关闭接收友链邀请"));
                }
                return Mono.zip(
                    friendManagementService.list(new AstraHubFriendManagementService.FriendInvitationsQuery("inbox", "")),
                    client.listAll(FriendInvitation.class, new ListOptions(), Sort.by("metadata.creationTimestamp")).collectList()
                )
                    .flatMap(syncTuple -> {
                        AstraHubFriendManagementService.FriendInvitationsQueryResult remote = syncTuple.getT1();
                        List<FriendInvitation> local = syncTuple.getT2();
                        if (!remote.success()) {
                            return Mono.just(new SyncResult(false, 0, 0, remote.message()));
                        }
                        return mergeInbox(remote.items(), local);
                    });
            })
            .doFinally(signalType -> syncing.set(false));
    }

    private Mono<SyncResult> mergeInbox(
        List<AstraHubFriendManagementService.FriendInvitationItem> remoteItems,
        List<FriendInvitation> localItems
    ) {
        Map<String, FriendInvitation> localByInviteId = new HashMap<>();
        for (FriendInvitation local : localItems) {
            if (local.getSpec() == null || local.getSpec().getDirection() != FriendInvitation.InvitationDirection.inbox) {
                continue;
            }
            String inviteId = trim(local.getSpec().getInvitationId());
            if (!inviteId.isEmpty()) {
                localByInviteId.put(inviteId, local);
            }
        }

        return Mono.defer(() -> {
            java.util.Set<String> remoteInviteIds = new java.util.HashSet<>();
            for (AstraHubFriendManagementService.FriendInvitationItem remote : remoteItems) {
                remoteInviteIds.add(trim(remote.inviteId()));
            }
            Mono<Integer> chain = Mono.just(0);
            for (AstraHubFriendManagementService.FriendInvitationItem remote : remoteItems) {
                chain = chain.flatMap(count ->
                    upsertInboxInvitation(remote, localByInviteId.get(trim(remote.inviteId())))
                        .map(changed -> changed ? count + 1 : count)
                );
            }
            for (FriendInvitation local : localByInviteId.values()) {
                String inviteId = trim(local.getSpec() == null ? null : local.getSpec().getInvitationId());
                if (inviteId.isEmpty() || remoteInviteIds.contains(inviteId)) {
                    continue;
                }
                chain = chain.flatMap(count -> deleteInboxInvitation(local).map(changed -> changed ? count + 1 : count));
            }
            return chain.map(changedCount -> new SyncResult(true, remoteItems.size(), changedCount, "ok"));
        });
    }

    private Mono<Boolean> upsertInboxInvitation(
        AstraHubFriendManagementService.FriendInvitationItem remote,
        FriendInvitation existing
    ) {
        FriendInvitation target = existing != null ? existing : new FriendInvitation();
        if (target.getMetadata() == null) {
            target.setMetadata(new Metadata());
        }
        if (trim(target.getMetadata().getName()).isEmpty()) {
            target.getMetadata().setName("inbox-" + trim(remote.inviteId()));
        }

        FriendInvitation.FriendInvitationSpec nextSpec = target.getSpec() == null
            ? new FriendInvitation.FriendInvitationSpec()
            : target.getSpec();
        nextSpec.setInvitationId(trim(remote.inviteId()));
        nextSpec.setDirection(FriendInvitation.InvitationDirection.inbox);
        nextSpec.setStatus(toInvitationStatus(remote.status()));
        nextSpec.setDeliveryStatus(trim(remote.deliveryStatus()));
        nextSpec.setPeerSiteId(trim(remote.fromSite().siteId()));
        nextSpec.setPeerSiteName(trim(remote.fromSite().siteName()));
        nextSpec.setPeerSiteUrl(trim(remote.fromSite().siteUrl()));
        nextSpec.setPeerSiteDescription(trim(remote.fromSite().description()));
        nextSpec.setPeerSiteAvatar(trim(remote.fromSite().avatarUrl()));
        nextSpec.setPeerSiteRssUrl(trim(remote.fromSite().rssUrl()));
        nextSpec.setPeerContactEmail("");
        nextSpec.setMessage(trim(remote.message()));
        nextSpec.setReviewReason(trim(remote.reviewReason()));
        nextSpec.setLinkGroupName(trim(remote.linkGroupName()));
        nextSpec.setCreatedAt(trim(remote.createdAt()));
        nextSpec.setUpdatedAt(trim(remote.updatedAt()));
        nextSpec.setReviewedAt(trim(remote.reviewedAt()));
        nextSpec.setAckedAt(trim(remote.ackedAt()));
        nextSpec.setLastError(trim(remote.lastError()));
        nextSpec.setRetryCount(remote.retryCount());
        target.setSpec(nextSpec);

        if (existing == null) {
            return client.create(target).map(created -> true);
        }
        return client.update(target).map(updated -> true);
    }

    private Mono<Boolean> deleteInboxInvitation(FriendInvitation local) {
        return client.delete(local).thenReturn(true);
    }

    private Mono<Boolean> readHubConfigured() {
        return Mono.zip(
                settingFetcher.getSettingValue("connection")
                    .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
                    .onErrorResume(error -> Mono.just(MAPPER.createObjectNode())),
                settingFetcher.getSettingValue("credentials")
                    .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
                    .onErrorResume(error -> Mono.just(MAPPER.createObjectNode()))
            )
            .map(tuple -> {
                String hubBaseUrl = trim(tuple.getT1().path("hubBaseUrl").asText(""));
                String siteId = trim(tuple.getT2().path("siteId").asText(""));
                return !hubBaseUrl.isEmpty() && !siteId.isEmpty();
            })
            .onErrorReturn(false);
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

    private static FriendInvitation.InvitationStatus toInvitationStatus(String raw) {
        String value = trim(raw).toLowerCase();
        return switch (value) {
            case "accepted" -> FriendInvitation.InvitationStatus.accepted;
            case "rejected" -> FriendInvitation.InvitationStatus.rejected;
            case "cancelled" -> FriendInvitation.InvitationStatus.cancelled;
            case "expired" -> FriendInvitation.InvitationStatus.expired;
            default -> FriendInvitation.InvitationStatus.pending;
        };
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record SyncResult(
        boolean success,
        int total,
        int changed,
        String message
    ) {
    }

    public record LocalFriendInvitationsResult(
        boolean success,
        int total,
        List<FriendInvitation> items
    ) {
    }
}
