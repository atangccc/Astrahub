package run.halo.astrahub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Unstructured;
import run.halo.astrahub.model.Link;
import run.halo.astrahub.model.LinkGroup;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubFriendLinkReconcileService {

    private static final ObjectMapper OBJECT_MAPPER = Unstructured.OBJECT_MAPPER;

    private final ReactiveExtensionClient client;

    public Mono<ReconcileResult> reconcile(AstraHubFriendManagementService.FriendInvitationItem invitation, String currentSiteId) {
        AstraHubFriendManagementService.FriendInvitationSiteInfo peer = resolvePeer(invitation, currentSiteId);
        String inviteId = trim(invitation.inviteId());
        String peerSiteId = trim(peer.siteId());
        String peerSiteUrl = trim(peer.siteUrl());
        if (inviteId.isEmpty()) {
            return Mono.just(ReconcileResult.failed("inviteId is required"));
        }
        if (peerSiteId.isEmpty() && peerSiteUrl.isEmpty()) {
            return Mono.just(ReconcileResult.failed("peer site info is missing"));
        }

        return client.listAll(Link.class, new ListOptions(), Sort.unsorted())
            .collectList()
            .flatMap(links -> {
                for (Link existing : links) {
                    String existingUrl = trim(existing.getSpec() == null ? null : existing.getSpec().getUrl());
                    if (!peerSiteUrl.isEmpty() && peerSiteUrl.equalsIgnoreCase(existingUrl)) {
                        return Mono.just(ReconcileResult.duplicate(inviteId, peerSiteId, peerSiteUrl));
                    }
                }

                return resolveLocalGroupName(trim(invitation.linkGroupName()))
                    .flatMap(resolvedGroupName -> {
                        Link link = new Link();
                        link.setMetadata(new Metadata());
                        link.getMetadata().setGenerateName("link-");
                        link.getMetadata().setAnnotations(buildAnnotations(invitation, peer));

                        Link.LinkSpec spec = new Link.LinkSpec();
                        spec.setUrl(peerSiteUrl);
                        spec.setDisplayName(firstNonEmpty(peer.siteName(), peerSiteUrl));
                        spec.setDescription(trim(peer.description()));
                        spec.setLogo(trim(peer.avatarUrl()));
                        spec.setPriority(0);
                        spec.setGroupName(resolvedGroupName.isEmpty() ? null : resolvedGroupName);
                        link.setSpec(spec);

                        return createByUnstructured(link)
                            .map(created -> ReconcileResult.created(inviteId, peerSiteId, peerSiteUrl, trim(created.getMetadata() == null ? null : created.getMetadata().getName())))
                            .onErrorResume(error -> {
                                log.warn("[AstraHub] create local link failed", error);
                                return Mono.just(ReconcileResult.failed("create local link failed: " + error.getMessage()));
                            });
                    });
            });
    }

    /**
     * 校验 groupName 是否真实存在于本站 LinkGroup。
     */
    private Mono<String> resolveLocalGroupName(String groupName) {
        if (groupName.isEmpty()) {
            return Mono.just("");
        }
        return client.fetch(LinkGroup.class, groupName)
            .map(group -> groupName)
            .defaultIfEmpty("")
            .onErrorResume(error -> {
                log.warn("[AstraHub] resolve local link group failed, fallback to ungrouped", error);
                return Mono.just("");
            });
    }

    private static AstraHubFriendManagementService.FriendInvitationSiteInfo resolvePeer(
        AstraHubFriendManagementService.FriendInvitationItem invitation,
        String currentSiteId
    ) {
        String normalizedCurrentSiteId = trim(currentSiteId);
        if (normalizedCurrentSiteId.equals(trim(invitation.toSite().siteId()))) {
            return invitation.fromSite();
        }
        return invitation.toSite();
    }

    private static Map<String, String> buildAnnotations(
        AstraHubFriendManagementService.FriendInvitationItem invitation,
        AstraHubFriendManagementService.FriendInvitationSiteInfo peer
    ) {
        Map<String, String> annotations = new LinkedHashMap<>();
        putIfPresent(annotations, "rss_url", peer.rssUrl());
        putIfPresent(annotations, "astrahub.io/invite-id", invitation.inviteId());
        putIfPresent(annotations, "astrahub.io/peer-site-id", peer.siteId());
        putIfPresent(annotations, "astrahub.io/peer-site-url", peer.siteUrl());
        putIfPresent(annotations, "astrahub.io/peer-rss-url", peer.rssUrl());
        return annotations;
    }

    private Mono<Link> createByUnstructured(Link link) {
        Map<?, ?> extensionMap = OBJECT_MAPPER.convertValue(link, Map.class);
        var extension = new Unstructured(extensionMap);
        return client.create(extension)
            .map(unstructured -> OBJECT_MAPPER.convertValue(unstructured, Link.class));
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        String trimmed = trim(value);
        if (!trimmed.isEmpty()) {
            target.put(key, trimmed);
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String trimmed = trim(value);
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 站点 A 在 Hub 更新资料后，Hub 通过 ws 广播 site_profile_updated。
     */
    public Mono<LocalLinkUpdateResult> updateLocalLinkByPeerSiteId(
        String peerSiteId, String name, String url, String description, String logo, String rssUrl
    ) {
        String normalizedPeerSiteId = trim(peerSiteId);
        if (normalizedPeerSiteId.isEmpty()) {
            return Mono.just(LocalLinkUpdateResult.failed("peerSiteId is required"));
        }
        return client.listAll(Link.class, new ListOptions(), Sort.unsorted())
            .filter(link -> normalizedPeerSiteId.equals(annotation(link, "astrahub.io/peer-site-id")))
            .map(link -> trim(link.getMetadata() == null ? null : link.getMetadata().getName()))
            .filter(metaName -> !metaName.isEmpty())
            .collectList()
            .flatMap(names -> {
                if (names.isEmpty()) {
                    return Mono.just(LocalLinkUpdateResult.notFound(normalizedPeerSiteId));
                }
                return reactor.core.publisher.Flux.fromIterable(names)
                    .flatMap(metaName -> updateLinkByNameWithRetry(metaName, name, url, description, logo, rssUrl))
                    .then(Mono.just(LocalLinkUpdateResult.updated(names.size(), normalizedPeerSiteId)));
            })
            .onErrorResume(error -> {
                log.warn("[AstraHub] update local link by peer site id failed", error);
                return Mono.just(LocalLinkUpdateResult.failed("update local link failed: " + error.getMessage()));
            });
    }

    /**
     * Fetch-mutate-update one Link by metadata.name, retrying on optimistic lock.
     * Overwrites identity fields with the hub's authoritative profile.
     */
    private Mono<Void> updateLinkByNameWithRetry(
        String metaName, String name, String url, String description, String logo, String rssUrl
    ) {
        return client.fetch(Link.class, metaName)
            .flatMap(latest -> {
                if (latest.getSpec() == null) {
                    latest.setSpec(new Link.LinkSpec());
                }
                String resolvedUrl = trim(url);
                latest.getSpec().setDisplayName(firstNonEmpty(name, resolvedUrl));
                if (!resolvedUrl.isEmpty()) {
                    latest.getSpec().setUrl(resolvedUrl);
                }
                latest.getSpec().setDescription(trim(description));
                latest.getSpec().setLogo(trim(logo));
                if (latest.getMetadata().getAnnotations() == null) {
                    latest.getMetadata().setAnnotations(new LinkedHashMap<>());
                }
                Map<String, String> annotations = latest.getMetadata().getAnnotations();
                if (!resolvedUrl.isEmpty()) {
                    annotations.put("astrahub.io/peer-site-url", resolvedUrl);
                }
                String resolvedRss = trim(rssUrl);
                annotations.put("rss_url", resolvedRss);
                annotations.put("astrahub.io/peer-rss-url", resolvedRss);

                Map<?, ?> extensionMap = OBJECT_MAPPER.convertValue(latest, Map.class);
                var extension = new Unstructured(extensionMap);
                return client.update(extension).then();
            })
            .retryWhen(Retry.backoff(5, Duration.ofMillis(100))
                .filter(OptimisticLockingFailureException.class::isInstance))
            .then();
    }

    private static String annotation(Link link, String key) {
        if (link == null || link.getMetadata() == null || link.getMetadata().getAnnotations() == null) {
            return "";
        }
        return trim(link.getMetadata().getAnnotations().get(key));
    }

    /**
     * Idempotently deletes the local Halo Link CR pointing at the given peer URL.
     * Used after a friend relation is removed on the hub.
     */
    public Mono<LocalLinkDeleteResult> deleteLocalLinkByPeerUrl(String peerUrl) {
        String normalizedPeerUrl = trim(peerUrl);
        if (normalizedPeerUrl.isEmpty()) {
            return Mono.just(LocalLinkDeleteResult.failed("peerUrl is required"));
        }
        return client.listAll(Link.class, new ListOptions(), Sort.unsorted())
            .filter(link -> {
                String existingUrl = trim(link.getSpec() == null ? null : link.getSpec().getUrl());
                return !existingUrl.isEmpty() && normalizedPeerUrl.equalsIgnoreCase(existingUrl);
            })
            .map(link -> trim(link.getMetadata() == null ? null : link.getMetadata().getName()))
            .filter(name -> !name.isEmpty())
            .collectList()
            .flatMap(names -> {
                if (names.isEmpty()) {
                    return Mono.just(LocalLinkDeleteResult.notFound(normalizedPeerUrl));
                }
                return reactor.core.publisher.Flux.fromIterable(names)
                    .flatMap(this::deleteLinkByNameWithRetry)
                    .then(Mono.just(LocalLinkDeleteResult.deleted(names.size(), normalizedPeerUrl)));
            })
            .onErrorResume(error -> {
                log.warn("[AstraHub] delete local link by peer url failed", error);
                return Mono.just(LocalLinkDeleteResult.failed("delete local link failed: " + error.getMessage()));
            });
    }

    /**
     * Idempotently deletes a Link by metadata.name. Uses Unstructured to route by
     * GVK against the official Link plugin's indices, retries on optimistic lock,
     * and treats a vanished record as success.
     */
    private Mono<Void> deleteLinkByNameWithRetry(String name) {
        return client.fetch(Link.class, name)
            .flatMap(latest -> {
                Map<?, ?> extensionMap = OBJECT_MAPPER.convertValue(latest, Map.class);
                var extension = new Unstructured(extensionMap);
                return client.delete(extension).then();
            })
            .retryWhen(Retry.backoff(5, Duration.ofMillis(100))
                .filter(OptimisticLockingFailureException.class::isInstance))
            .onErrorResume(OptimisticLockingFailureException.class, error ->
                client.fetch(Link.class, name)
                    .flatMap(stillThere -> Mono.<Void>error(error))
                    .switchIfEmpty(Mono.empty())
            );
    }

    public record LocalLinkUpdateResult(
        boolean success,
        int updated,
        String peerSiteId,
        String message
    ) {
        public static LocalLinkUpdateResult updated(int count, String peerSiteId) {
            return new LocalLinkUpdateResult(true, count, peerSiteId, "updated");
        }

        public static LocalLinkUpdateResult notFound(String peerSiteId) {
            // 没找到也算成功（幂等：旧数据无 annotation 或用户已手删，重复触发不报错）。
            return new LocalLinkUpdateResult(true, 0, peerSiteId, "not_found");
        }

        public static LocalLinkUpdateResult failed(String message) {
            return new LocalLinkUpdateResult(false, 0, "", message);
        }
    }

    public record LocalLinkDeleteResult(
        boolean success,
        int deleted,
        String peerUrl,
        String message
    ) {
        public static LocalLinkDeleteResult deleted(int count, String peerUrl) {
            return new LocalLinkDeleteResult(true, count, peerUrl, "deleted");
        }

        public static LocalLinkDeleteResult notFound(String peerUrl) {
            // 没找到也算成功（幂等：重复触发同一删除指令不会报错）。
            return new LocalLinkDeleteResult(true, 0, peerUrl, "not_found");
        }

        public static LocalLinkDeleteResult failed(String message) {
            return new LocalLinkDeleteResult(false, 0, "", message);
        }
    }

    public record ReconcileResult(
        boolean success,
        boolean created,
        boolean duplicate,
        String message,
        String inviteId,
        String peerSiteId,
        String peerSiteUrl,
        String linkName
    ) {
        public static ReconcileResult created(String inviteId, String peerSiteId, String peerSiteUrl, String linkName) {
            return new ReconcileResult(true, true, false, "created", inviteId, peerSiteId, peerSiteUrl, linkName);
        }

        public static ReconcileResult duplicate(String inviteId, String peerSiteId, String peerSiteUrl) {
            return new ReconcileResult(true, false, true, "duplicate", inviteId, peerSiteId, peerSiteUrl, "");
        }

        public static ReconcileResult failed(String message) {
            return new ReconcileResult(false, false, false, message, "", "", "", "");
        }
    }
}
