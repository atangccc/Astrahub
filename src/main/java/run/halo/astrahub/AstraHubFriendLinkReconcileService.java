package run.halo.astrahub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Unstructured;
import run.halo.astrahub.model.Link;

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
                spec.setGroupName(trim(invitation.linkGroupName()));
                link.setSpec(spec);

                return createByUnstructured(link)
                    .map(created -> ReconcileResult.created(inviteId, peerSiteId, peerSiteUrl, trim(created.getMetadata() == null ? null : created.getMetadata().getName())))
                    .onErrorResume(error -> {
                        log.warn("[AstraHub] create local link failed", error);
                        return Mono.just(ReconcileResult.failed("create local link failed: " + error.getMessage()));
                    });
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
