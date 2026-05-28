package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.astrahub.model.Link;
import run.halo.astrahub.model.LinkGroup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubCollectionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final String PLUGIN_LINKS_API =
        "/apis/api.plugin.halo.run/v1alpha1/plugins/PluginLinks/links?keyword=&page=1&size=2000&sort=spec.priority%2Casc";

    private final ReactiveExtensionClient client;

    public Mono<CollectedPayload> collect() {
        return collectInternal(null);
    }

    public Mono<CollectedPayload> collect(ServerRequest request) {
        return collectInternal(request);
    }

    private Mono<CollectedPayload> collectInternal(ServerRequest request) {
        return Mono.zip(listLinks(request), listLinkGroups())
            .map(tuple -> buildPayload(tuple.getT1(), tuple.getT2()));
    }

    private Mono<List<Link>> listLinks(ServerRequest request) {
        return listLinksFromExtensions()
            .flatMap(extensionLinks -> {
                if (request == null) {
                    return Mono.just(extensionLinks);
                }
                return listLinksFromPluginApi(request)
                    .map(fallbackLinks -> mergeLinks(extensionLinks, fallbackLinks));
            });
    }

    private Mono<List<Link>> listLinksFromExtensions() {
        return client.listAll(Link.class, new ListOptions(), Sort.by("spec.priority"))
            .collectList()
            .onErrorResume(error -> {
                log.warn("[AstraHub] failed to list links from extensions", error);
                return Mono.just(List.of());
            });
    }

    private Mono<List<Link>> listLinksFromPluginApi(ServerRequest request) {
        return Mono.fromCallable(() -> listLinksFromPluginApiBlocking(request))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(error -> {
                log.warn("[AstraHub] failed to list links from PluginLinks API", error);
                return Mono.just(List.of());
            });
    }

    private List<Link> listLinksFromPluginApiBlocking(ServerRequest request) throws Exception {
        String baseUrl = resolveBaseUrl(request);
        if (baseUrl.isEmpty()) {
            return List.of();
        }

        URI endpoint = URI.create(baseUrl + PLUGIN_LINKS_API);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/json")
            .GET();

        HttpResponse<String> response = HTTP_CLIENT.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("[AstraHub] PluginLinks API returned status {}", response.statusCode());
            return List.of();
        }

        String body = Optional.ofNullable(response.body()).orElse("");
        if (body.isBlank()) {
            return List.of();
        }

        JsonNode root = MAPPER.readTree(body);
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            items = root.path("data").path("items");
        }
        if (!items.isArray() && root.isArray()) {
            items = root;
        }
        if (!items.isArray()) {
            return List.of();
        }

        List<Link> links = new ArrayList<>();
        for (JsonNode item : items) {
            try {
                Link link = MAPPER.convertValue(item, Link.class);
                if (link != null) {
                    links.add(link);
                }
            } catch (Exception ignored) {
            }
        }

        if (!links.isEmpty()) {
            log.info("[AstraHub] collected {} links from PluginLinks API fallback", links.size());
        }
        return links;
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
                readCreatedAt(link)
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

    private static String resolveBaseUrl(ServerRequest request) {
        if (request == null) {
            return "";
        }

        String proto = firstHeaderValue(request, "X-Forwarded-Proto");
        if (proto.isEmpty()) {
            proto = parseForwardedField(request.headers().firstHeader("Forwarded"), "proto");
        }
        if (proto.isEmpty()) {
            proto = trim(request.uri().getScheme());
        }

        String host = firstHeaderValue(request, "X-Forwarded-Host");
        if (host.isEmpty()) {
            host = parseForwardedField(request.headers().firstHeader("Forwarded"), "host");
        }
        if (host.isEmpty()) {
            host = firstHeaderValue(request, "Host");
        }
        if (host.isEmpty()) {
            host = trim(request.uri().getAuthority());
        }

        if (proto.isEmpty() || host.isEmpty()) {
            return "";
        }
        return proto + "://" + host;
    }

    private static List<Link> mergeLinks(List<Link> extensionLinks, List<Link> fallbackLinks) {
        Map<String, Link> merged = new LinkedHashMap<>();
        safeList(extensionLinks).forEach(link -> {
            String name = readName(link);
            if (!name.isEmpty()) {
                merged.putIfAbsent(name, link);
            }
        });
        safeList(fallbackLinks).forEach(link -> {
            String name = readName(link);
            if (!name.isEmpty()) {
                merged.putIfAbsent(name, link);
            }
        });
        return new ArrayList<>(merged.values());
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

    private static String firstHeaderValue(ServerRequest request, String header) {
        String raw = trim(request.headers().firstHeader(header));
        if (raw.isEmpty()) {
            return "";
        }
        int comma = raw.indexOf(',');
        if (comma >= 0) {
            return trim(raw.substring(0, comma));
        }
        return raw;
    }

    private static String parseForwardedField(String header, String key) {
        String raw = trim(header);
        if (raw.isEmpty()) {
            return "";
        }

        String first = raw;
        int comma = first.indexOf(',');
        if (comma >= 0) {
            first = first.substring(0, comma);
        }

        for (String token : first.split(";")) {
            String value = trim(token);
            if (!value.regionMatches(true, 0, key + "=", 0, key.length() + 1)) {
                continue;
            }
            String extracted = trim(value.substring(key.length() + 1));
            if (extracted.startsWith("\"") && extracted.endsWith("\"") && extracted.length() >= 2) {
                extracted = extracted.substring(1, extracted.length() - 1);
            }
            return trim(extracted);
        }
        return "";
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
        String createdAt
    ) {
    }
}
