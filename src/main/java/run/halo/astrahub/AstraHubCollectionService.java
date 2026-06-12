package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.astrahub.model.Link;
import run.halo.astrahub.model.LinkGroup;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubCollectionService {

    private final ReactiveExtensionClient client;

    public Mono<CollectedPayload> collect() {
        return collectInternal();
    }

    public Mono<CollectedPayload> collect(ServerRequest request) {
        return collectInternal();
    }

    private Mono<CollectedPayload> collectInternal() {
        return Mono.zip(listLinks(), listLinkGroups())
            .map(tuple -> buildPayload(tuple.getT1(), tuple.getT2()));
    }

    private Mono<List<Link>> listLinks() {
        return client.listAll(Link.class, new ListOptions(), Sort.by("spec.priority"))
            .collectList()
            .onErrorResume(error -> {
                log.warn("[AstraHub] failed to list links from extensions", error);
                return Mono.just(List.of());
            });
    }

    private Mono<List<LinkGroup>> listLinkGroups() {
        return client.listAll(LinkGroup.class, new ListOptions(), Sort.by("spec.priority"))
            .collectList()
            .onErrorResume(error -> {
                log.warn("[AstraHub] failed to list link groups", error);
                return Mono.just(List.of());
            });
    }

    private CollectedPayload buildPayload(List<Link> links, List<LinkGroup> groups) {
        Map<String, Link> linkByName = new LinkedHashMap<>();
        for (Link link : links) {
            String name = readName(link);
            if (name.isEmpty()) {
                continue;
            }
            linkByName.putIfAbsent(name, link);
        }

        Map<String, String> groupNameById = new LinkedHashMap<>();
        Map<String, Integer> groupPriorityById = new LinkedHashMap<>();
        Map<String, Set<String>> groupToLinkNames = new LinkedHashMap<>();
        Map<String, Set<String>> linkToGroupIds = new LinkedHashMap<>();

        for (LinkGroup group : groups) {
            String groupId = readName(group);
            if (groupId.isEmpty()) {
                continue;
            }
            groupNameById.putIfAbsent(groupId, readGroupDisplayName(group, groupId));
            groupPriorityById.putIfAbsent(groupId, readGroupPriority(group));

            Set<String> linkNameSet = new LinkedHashSet<>();
            safeList(group.getSpec() == null ? null : group.getSpec().getLinks())
                .stream()
                .map(AstraHubCollectionService::trim)
                .filter(v -> !v.isEmpty())
                .forEach(linkNameSet::add);

            safeList(group.getLinks())
                .forEach(groupLink -> {
                    String linkName = readName(groupLink);
                    if (linkName.isEmpty()) {
                        return;
                    }
                    linkNameSet.add(linkName);
                    linkByName.putIfAbsent(linkName, groupLink);
                });

            for (String linkName : linkNameSet) {
                linkToGroupIds.computeIfAbsent(linkName, key -> new LinkedHashSet<>()).add(groupId);
                groupToLinkNames.computeIfAbsent(groupId, key -> new LinkedHashSet<>()).add(linkName);
            }
        }

        List<LinkSnapshot> linkSnapshots = new ArrayList<>();
        for (Map.Entry<String, Link> entry : linkByName.entrySet()) {
            String linkName = entry.getKey();
            Link link = entry.getValue();
            String url = normalizeLinkUrl(readLinkUrl(link));
            if (url.isEmpty()) {
                continue;
            }

            Set<String> groupIdSet = new LinkedHashSet<>(linkToGroupIds.getOrDefault(linkName, Set.of()));
            String directGroupId = readLinkGroupName(link);
            if (!directGroupId.isEmpty()) {
                groupIdSet.add(directGroupId);
            }

            for (String groupId : groupIdSet) {
                groupNameById.putIfAbsent(groupId, groupId);
                groupPriorityById.putIfAbsent(groupId, 0);
                groupToLinkNames.computeIfAbsent(groupId, key -> new LinkedHashSet<>()).add(linkName);
            }

            List<String> groupIds = new ArrayList<>(groupIdSet);
            groupIds.sort(String::compareTo);

            LinkSnapshot snapshot = new LinkSnapshot(
                linkName,
                readLinkTitle(link, linkName),
                url,
                trim(link.getSpec() == null ? null : link.getSpec().getDescription()),
                trim(link.getSpec() == null ? null : link.getSpec().getLogo()),
                readLinkRssURL(link),
                readLinkPriority(link),
                groupIds,
                readCreatedAt(link),
                readLinkPeerSiteId(link)
            );
            linkSnapshots.add(snapshot);
        }

        linkSnapshots.sort(Comparator
            .comparingInt(LinkSnapshot::priority)
            .thenComparing(LinkSnapshot::title));

        List<GroupSnapshot> groupSnapshots = new ArrayList<>();
        for (Map.Entry<String, String> groupEntry : groupNameById.entrySet()) {
            String groupId = groupEntry.getKey();
            List<String> linkNames = new ArrayList<>(groupToLinkNames.getOrDefault(groupId, Set.of()));
            linkNames.sort(String::compareTo);
            groupSnapshots.add(new GroupSnapshot(
                groupId,
                groupEntry.getValue(),
                groupPriorityById.getOrDefault(groupId, 0),
                linkNames
            ));
        }
        groupSnapshots.sort(Comparator.comparingInt(GroupSnapshot::priority).thenComparing(GroupSnapshot::name));

        long groupedLinks = linkSnapshots.stream()
            .filter(link -> !link.groupExternalIds().isEmpty())
            .count();
        long standaloneLinks = linkSnapshots.size() - groupedLinks;

        return new CollectedPayload(
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            linkSnapshots.size(),
            groupSnapshots.size(),
            (int) groupedLinks,
            (int) standaloneLinks,
            groupSnapshots,
            linkSnapshots
        );
    }

    private static String normalizeLinkUrl(String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return "https://" + value;
    }

    private static String readName(run.halo.app.extension.AbstractExtension extension) {
        if (extension == null || extension.getMetadata() == null) {
            return "";
        }
        return trim(extension.getMetadata().getName());
    }

    private static String readGroupDisplayName(LinkGroup group, String fallback) {
        if (group == null || group.getSpec() == null) {
            return fallback;
        }
        String displayName = trim(group.getSpec().getDisplayName());
        return displayName.isEmpty() ? fallback : displayName;
    }

    private static int readGroupPriority(LinkGroup group) {
        if (group == null || group.getSpec() == null || group.getSpec().getPriority() == null) {
            return 0;
        }
        return group.getSpec().getPriority();
    }

    private static String readLinkGroupName(Link link) {
        if (link == null || link.getSpec() == null) {
            return "";
        }
        return trim(link.getSpec().getGroupName());
    }

    private static String readLinkUrl(Link link) {
        if (link == null || link.getSpec() == null) {
            return "";
        }
        return trim(link.getSpec().getUrl());
    }

    private static String readLinkTitle(Link link, String fallback) {
        if (link == null || link.getSpec() == null) {
            return fallback;
        }
        String displayName = trim(link.getSpec().getDisplayName());
        return displayName.isEmpty() ? fallback : displayName;
    }

    private static int readLinkPriority(Link link) {
        if (link == null || link.getSpec() == null || link.getSpec().getPriority() == null) {
            return 0;
        }
        return link.getSpec().getPriority();
    }

    private static String readLinkRssURL(Link link) {
        if (link == null || link.getMetadata() == null || link.getMetadata().getAnnotations() == null) {
            return "";
        }
        return trim(link.getMetadata().getAnnotations().get("rss_url"));
    }

    private static String readLinkPeerSiteId(Link link) {
        if (link == null || link.getMetadata() == null || link.getMetadata().getAnnotations() == null) {
            return "";
        }
        return trim(link.getMetadata().getAnnotations().get("astrahub.io/peer-site-id"));
    }

    private static String readCreatedAt(Link link) {
        if (link == null || link.getMetadata() == null || link.getMetadata().getCreationTimestamp() == null) {
            return "";
        }
        return link.getMetadata().getCreationTimestamp().toString();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record CollectedPayload(
        String collectedAt,
        int totalLinks,
        int totalGroups,
        int groupedLinks,
        int standaloneLinks,
        List<GroupSnapshot> groups,
        List<LinkSnapshot> links
    ) {
    }

    public record GroupSnapshot(
        String externalId,
        String name,
        int priority,
        List<String> linkNames
    ) {
    }

    public record LinkSnapshot(
        String externalId,
        String title,
        String url,
        String description,
        String logo,
        String rssUrl,
        int priority,
        List<String> groupExternalIds,
        String createdAt,
        String peerSiteId
    ) {
    }
}
