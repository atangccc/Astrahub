package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubGraphTransformService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GRAPH_VERSION = "bp.graph.v1";
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b");
    private static final Pattern MARKDOWN_LINK_PATTERN =
        Pattern.compile("(?<!\\!)\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern HTML_LINK_PATTERN =
        Pattern.compile("(?i)<a\\b([^>]*)href\\s*=\\s*['\"]([^'\"]+)['\"]([^>]*)>(.*?)</a>");
    private static final Pattern REL_PATTERN =
        Pattern.compile("(?i)rel\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Set<String> TRACKING_QUERY_KEYS = Set.of(
        "fbclid", "gclid", "igshid", "mc_cid", "mc_eid", "ref", "source", "spm"
    );

    private final AstraHubCollectionService collectionService;
    private final AstraHubContentCollectionService contentCollectionService;
    private final ReactiveSettingFetcher settingFetcher;

    public Mono<GraphPayload> buildBpGraphV1Payload() {
        return buildBpGraphV1Payload(null, "");
    }

    public Mono<GraphPayload> buildBpGraphV1Payload(String syncReason) {
        return buildBpGraphV1Payload(null, "", syncReason);
    }

    public Mono<GraphPayload> buildBpGraphV1Payload(ServerRequest request) {
        return buildBpGraphV1Payload(request, "", "");
    }

    public Mono<GraphPayload> buildBpGraphV1Payload(ServerRequest request, String syncReason) {
        return buildBpGraphV1Payload(request, "", syncReason);
    }

    public Mono<GraphPayload> buildBpGraphV1PayloadSince(String updatedSince) {
        return buildBpGraphV1Payload(null, updatedSince, "");
    }

    public Mono<GraphPayload> buildBpGraphV1PayloadSince(String updatedSince, String syncReason) {
        return buildBpGraphV1Payload(null, updatedSince, syncReason);
    }

    public Mono<GraphPayload> buildBpGraphV1PayloadSince(String updatedSince, ServerRequest request) {
        return buildBpGraphV1Payload(request, updatedSince, "");
    }

    public Mono<GraphPayload> buildBpGraphV1PayloadSince(String updatedSince, ServerRequest request, String syncReason) {
        return buildBpGraphV1Payload(request, updatedSince, syncReason);
    }

    private Mono<GraphPayload> buildBpGraphV1Payload(ServerRequest request, String updatedSince, String syncReason) {
        Mono<AstraHubCollectionService.CollectedPayload> linksMono =
            request == null ? collectionService.collect() : collectionService.collect(request);
        Mono<AstraHubContentCollectionService.CollectedContentPayload> contentMono =
            trim(updatedSince).isEmpty()
                ? (request == null ? contentCollectionService.collect() : contentCollectionService.collect(request))
                : (request == null
                    ? contentCollectionService.collectIncremental(updatedSince)
                    : contentCollectionService.collectIncremental(updatedSince, request));

        return Mono.zip(
            contentMono,
            linksMono,
            readSetting("connection"),
            readSetting("credentials")
        ).map(tuple -> transform(
            tuple.getT1(),
            tuple.getT2(),
            tuple.getT3(),
            tuple.getT4(),
            syncReason
        ));
    }

    private Mono<JsonNode> readSetting(String key) {
        return settingFetcher.getSettingValue(key)
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .onErrorResume(error -> {
                log.debug("[AstraHub] failed to read setting '{}': {}", key, error.getMessage());
                return Mono.empty();
            })
            .switchIfEmpty(Mono.fromSupplier(MAPPER::createObjectNode));
    }

    private GraphPayload transform(
        AstraHubContentCollectionService.CollectedContentPayload contentPayload,
        AstraHubCollectionService.CollectedPayload linkPayload,
        JsonNode connection,
        JsonNode credentials,
        String syncReason
    ) {
        String snapshotAt = maxInstant(
            normalizeDateTime(contentPayload.collectedAt(), ""),
            normalizeDateTime(linkPayload.collectedAt(), "")
        );
        if (snapshotAt.isEmpty()) {
            snapshotAt = nowIso();
        }

        String nodeName = readString(connection, "siteNodeName", "");
        String nodeAvatar = readString(connection, "siteNodeAvatar", "");
        String siteRssUrl = readString(connection, "siteRssUrl", "");
        String siteUrl = readString(connection, "siteUrl", "");
        String normalizedSiteUrl = firstNonBlank(
            normalizeContentUrl(siteUrl, contentPayload.baseUrl()),
            normalizeContentUrl(contentPayload.baseUrl(), siteUrl),
            trim(siteUrl)
        );
        String normalizedSiteRssUrl = normalizeOptionalAbsoluteUrl(siteRssUrl, firstNonBlank(normalizedSiteUrl, contentPayload.baseUrl()));

        GraphSource source = new GraphSource(
            "halo",
            "astrahub",
            pluginVersion(),
            readString(credentials, "siteId", ""),
            readString(connection, "siteName", ""),
            normalizedSiteUrl,
            nodeName,
            nodeName,
            normalizeOptionalAbsoluteUrl(nodeAvatar, firstNonBlank(normalizedSiteUrl, contentPayload.baseUrl())),
            normalizedSiteRssUrl,
            normalizeSyncReason(syncReason),
            deriveOwner(contentPayload.contents()),
            deriveLanguage(contentPayload.contents())
        );

        GraphConsent graphConsent = new GraphConsent(true, "v1", snapshotAt);

        Map<String, GraphGroup> groupById = new LinkedHashMap<>();
        Map<String, FriendSiteRef> friendByExactUrl = new LinkedHashMap<>();
        Map<String, FriendSiteRef> friendBySiteRoot = new LinkedHashMap<>();
        Map<String, String> friendGroupNames = new LinkedHashMap<>();

        for (AstraHubCollectionService.GroupSnapshot group : linkPayload.groups()) {
            String externalId = "friend-group:" + trim(group.externalId());
            friendGroupNames.put(group.externalId(), group.name());
            groupById.putIfAbsent(externalId, new GraphGroup(
                externalId,
                group.name(),
                group.priority(),
                Map.of("groupType", "friend_link_group", "linkCount", group.linkNames().size())
            ));
        }

        for (AstraHubCollectionService.LinkSnapshot link : linkPayload.links()) {
            String normalizedUrl = normalizeContentUrl(link.url(), contentPayload.baseUrl());
            if (normalizedUrl.isEmpty()) {
                continue;
            }
            FriendSiteRef ref = new FriendSiteRef(
                normalizedUrl,
                normalizeSiteRoot(normalizedUrl),
                sanitizeEmailText(link.title())
            );
            friendByExactUrl.putIfAbsent(normalizedUrl, ref);
            if (!ref.siteRoot().isEmpty()) {
                friendBySiteRoot.putIfAbsent(ref.siteRoot(), ref);
            }
        }

        for (AstraHubContentCollectionService.CollectedContent content : contentPayload.contents()) {
            for (AstraHubContentCollectionService.GroupReference group : safeList(content.groups())) {
                groupById.putIfAbsent(group.externalId(), new GraphGroup(
                    group.externalId(),
                    group.name(),
                    group.priority(),
                    Map.of("groupType", group.type())
                ));
            }
        }

        String siteRoot = normalizeSiteRoot(firstNonBlank(contentPayload.baseUrl(), siteUrl));
        Map<String, String> sameSiteContentByUrl = new LinkedHashMap<>();
        for (AstraHubContentCollectionService.CollectedContent content : contentPayload.contents()) {
            String normalizedCanonical = normalizeContentUrl(content.canonicalUrl(), contentPayload.baseUrl());
            if (!normalizedCanonical.isEmpty()) {
                sameSiteContentByUrl.putIfAbsent(normalizedCanonical, content.externalId());
            }
        }

        List<GraphContent> contents = new ArrayList<>();
        for (AstraHubContentCollectionService.CollectedContent content : contentPayload.contents()) {
            RelationExtraction extraction = extractRelations(
                content,
                contentPayload.baseUrl(),
                siteRoot,
                sameSiteContentByUrl,
                friendByExactUrl,
                friendBySiteRoot
            );

            Map<String, Object> meta = cloneMap(content.meta());
            meta.put("sourceType", "halo-content");
            contents.add(new GraphContent(
                content.externalId(),
                normalizeContentUrl(content.canonicalUrl(), contentPayload.baseUrl()),
                sanitizeEmailText(content.title()),
                sanitizeEmailText(content.summary()),
                normalizeOptionalAbsoluteUrl(
                    content.cover(),
                    firstNonBlank(content.canonicalUrl(), normalizedSiteUrl, contentPayload.baseUrl())
                ),
                trim(content.author()),
                dedupeStrings(content.tags()),
                mergeGraphTopics(content.topics(), content.sourceCategory(), content.series()),
                sanitizeEmailText(content.sourceCategory()),
                dedupeStrings(content.series()),
                normalizeDateTime(content.createdAt(), snapshotAt),
                normalizeDateTime(content.updatedAt(), snapshotAt),
                normalizeDateTime(content.publishedAt(), snapshotAt),
                firstNonBlank(content.status(), "published"),
                firstNonBlank(content.visibility(), "public"),
                trim(content.language()),
                Math.max(content.wordCount(), 0),
                content.groups().stream().map(AstraHubContentCollectionService.GroupReference::externalId).toList(),
                extraction.outboundLinks(),
                extraction.mentionedSites(),
                extraction.relatedContentExternalIds(),
                meta
            ));
        }

        for (AstraHubCollectionService.LinkSnapshot link : linkPayload.links()) {
            List<String> groupIds = new ArrayList<>();
            List<String> topics = new ArrayList<>();
            for (String rawGroupId : safeList(link.groupExternalIds())) {
                String groupId = trim(rawGroupId);
                if (groupId.isEmpty()) {
                    continue;
                }
                groupIds.add("friend-group:" + groupId);
                String groupName = trim(friendGroupNames.get(groupId));
                if (!groupName.isEmpty()) {
                    topics.add(groupName);
                }
            }

            String linkUrl = normalizeContentUrl(link.url(), contentPayload.baseUrl());
            String createdAt = normalizeDateTime(link.createdAt(), snapshotAt);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sourceType", "friend-link");
            meta.put("priority", link.priority());
            String rssUrl = normalizeOptionalAbsoluteUrl(link.rssUrl(), firstNonBlank(linkUrl, normalizedSiteUrl, contentPayload.baseUrl()));
            if (!rssUrl.isEmpty()) {
                meta.put("rssUrl", rssUrl);
            }

            contents.add(new GraphContent(
                "friend-link:" + trim(link.externalId()),
                linkUrl,
                sanitizeEmailText(link.title()),
                sanitizeEmailText(link.description()),
                normalizeOptionalAbsoluteUrl(link.logo(), firstNonBlank(linkUrl, normalizedSiteUrl, contentPayload.baseUrl())),
                "",
                List.of(),
                dedupeStrings(topics),
                "",
                List.of(),
                createdAt,
                snapshotAt,
                snapshotAt,
                "published",
                "public",
                source.language(),
                0,
                dedupeStrings(groupIds),
                linkUrl.isEmpty() ? List.of() : List.of(new GraphOutboundLink(linkUrl, sanitizeEmailText(link.title()), "", "friend")),
                linkUrl.isEmpty() ? List.of() : List.of(new GraphMentionedSite(normalizeSiteRoot(linkUrl), sanitizeEmailText(link.title()), 1.0)),
                List.of(),
                meta
            ));
        }

        String selfLinkUrl = trim(source.siteUrl());
        if (!selfLinkUrl.isEmpty()) {
            Map<String, Object> selfMeta = new LinkedHashMap<>();
            selfMeta.put("sourceType", "self-link");
            if (!trim(source.siteRssUrl()).isEmpty()) {
                selfMeta.put("rssUrl", source.siteRssUrl());
            }

            contents.add(new GraphContent(
                "self-link:" + sanitizeExternalId(firstNonBlank(
                    source.siteId(),
                    normalizeSiteRoot(selfLinkUrl),
                    selfLinkUrl,
                    source.siteName(),
                    "site"
                )),
                selfLinkUrl,
                sanitizeEmailText(source.siteName()),
                "",
                normalizeOptionalAbsoluteUrl(source.nodeAvatar(), firstNonBlank(selfLinkUrl, normalizedSiteUrl, contentPayload.baseUrl())),
                "",
                List.of(),
                List.of(),
                "",
                List.of(),
                snapshotAt,
                snapshotAt,
                snapshotAt,
                "published",
                "public",
                source.language(),
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                selfMeta
            ));
        }

        List<GraphGroup> groups = new ArrayList<>(groupById.values());
        groups.sort(Comparator.comparingInt(GraphGroup::priority).thenComparing(GraphGroup::name));
        contents.sort(Comparator.comparing(GraphContent::publishedAt).reversed().thenComparing(GraphContent::title));

        return new GraphPayload(GRAPH_VERSION, source, snapshotAt, graphConsent, groups, contents);
    }

    private static RelationExtraction extractRelations(
        AstraHubContentCollectionService.CollectedContent content,
        String baseUrl,
        String siteRoot,
        Map<String, String> sameSiteContentByUrl,
        Map<String, FriendSiteRef> friendByExactUrl,
        Map<String, FriendSiteRef> friendBySiteRoot
    ) {
        LinkedHashMap<String, GraphOutboundLink> outboundByUrl = new LinkedHashMap<>();
        LinkedHashMap<String, GraphMentionedSite> mentionedBySite = new LinkedHashMap<>();
        LinkedHashSet<String> relatedIds = new LinkedHashSet<>();

        collectMarkdownLinks(content.rawContent(), content.canonicalUrl(), baseUrl, outboundByUrl);
        collectHtmlLinks(content.htmlContent(), content.canonicalUrl(), baseUrl, outboundByUrl);

        for (Map.Entry<String, GraphOutboundLink> entry : List.copyOf(outboundByUrl.entrySet())) {
            String targetUrl = entry.getKey();
            GraphOutboundLink link = entry.getValue();
            String targetRoot = normalizeSiteRoot(targetUrl);

            if (!siteRoot.isEmpty() && siteRoot.equals(targetRoot)) {
                String relatedId = sameSiteContentByUrl.get(targetUrl);
                if (relatedId != null && !relatedId.equals(content.externalId())) {
                    relatedIds.add(relatedId);
                }
                continue;
            }

            FriendSiteRef friendRef = Optional.ofNullable(friendByExactUrl.get(targetUrl))
                .orElseGet(() -> targetRoot.isEmpty() ? null : friendBySiteRoot.get(targetRoot));
            outboundByUrl.put(targetUrl, new GraphOutboundLink(
                targetUrl,
                link.anchorText(),
                link.rel(),
                classifyLinkType(link, friendRef)
            ));

            if (!targetRoot.isEmpty()) {
                mentionedBySite.putIfAbsent(targetRoot, new GraphMentionedSite(
                    targetRoot,
                    friendRef != null ? friendRef.siteName() : firstNonBlank(link.anchorText(), targetRoot),
                    friendRef != null ? 1.0 : 0.7
                ));
            }
        }

        return new RelationExtraction(
            new ArrayList<>(outboundByUrl.values()),
            new ArrayList<>(mentionedBySite.values()),
            new ArrayList<>(relatedIds)
        );
    }

    private static void collectMarkdownLinks(String rawContent, String canonicalUrl, String baseUrl, Map<String, GraphOutboundLink> outboundByUrl) {
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(firstNonBlank(rawContent, ""));
        while (matcher.find()) {
            String normalized = normalizeContentUrl(matcher.group(2), firstNonBlank(canonicalUrl, baseUrl));
            if (!normalized.isEmpty()) {
                outboundByUrl.putIfAbsent(normalized, new GraphOutboundLink(normalized, trim(matcher.group(1)), "", "reference"));
            }
        }
    }

    private static void collectHtmlLinks(String htmlContent, String canonicalUrl, String baseUrl, Map<String, GraphOutboundLink> outboundByUrl) {
        Matcher matcher = HTML_LINK_PATTERN.matcher(firstNonBlank(htmlContent, ""));
        while (matcher.find()) {
            String normalized = normalizeContentUrl(matcher.group(2), firstNonBlank(canonicalUrl, baseUrl));
            if (normalized.isEmpty()) {
                continue;
            }
            String attrs = firstNonBlank(matcher.group(1), "") + " " + firstNonBlank(matcher.group(3), "");
            GraphOutboundLink current = outboundByUrl.get(normalized);
            outboundByUrl.put(normalized, new GraphOutboundLink(
                normalized,
                firstNonBlank(current == null ? "" : current.anchorText(), AstraHubContentCollectionService.stripMarkup(matcher.group(4))),
                firstNonBlank(current == null ? "" : current.rel(), extractRel(attrs)),
                current == null ? "reference" : current.linkType()
            ));
        }
    }

    private static String classifyLinkType(GraphOutboundLink link, FriendSiteRef friendRef) {
        if (friendRef != null) {
            return "friend";
        }
        String rel = trim(link.rel()).toLowerCase(Locale.ROOT);
        String anchor = trim(link.anchorText()).toLowerCase(Locale.ROOT);
        if (rel.contains("citation") || rel.contains("cite") || anchor.contains("引用") || anchor.contains("参考")) {
            return "citation";
        }
        if (rel.isEmpty() && anchor.isEmpty()) {
            return "unknown";
        }
        return "reference";
    }

    private static String extractRel(String attributes) {
        Matcher matcher = REL_PATTERN.matcher(firstNonBlank(attributes, ""));
        return matcher.find() ? trim(matcher.group(1)) : "";
    }

    static String normalizeContentUrl(String rawUrl, String baseUrl) {
        String value = trim(rawUrl);
        if (value.isEmpty()
            || value.startsWith("#")
            || value.startsWith("mailto:")
            || value.startsWith("tel:")
            || value.startsWith("javascript:")) {
            return "";
        }
        try {
            URI base = baseUrl == null || baseUrl.isBlank() ? null : URI.create(baseUrl);
            URI uri = base == null ? URI.create(value) : base.resolve(value);
            String scheme = firstNonBlank(uri.getScheme(), "https").toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            String path = firstNonBlank(uri.getRawPath(), "/");
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = normalizeQuery(uri.getRawQuery());
            int port = uri.getPort();
            boolean usePort = port > 0 && !(scheme.equals("http") && port == 80) && !(scheme.equals("https") && port == 443);
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (usePort ? ":" + port : "") + path + (query.isEmpty() ? "" : "?" + query);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeQuery(String rawQuery) {
        String query = trim(rawQuery);
        if (query.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : query.split("&")) {
            String token = trim(part);
            if (token.isEmpty()) {
                continue;
            }
            String key = token.contains("=") ? token.substring(0, token.indexOf('=')) : token;
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (lowerKey.startsWith("utm_") || TRACKING_QUERY_KEYS.contains(lowerKey)) {
                continue;
            }
            parts.add(token);
        }
        return String.join("&", parts);
    }

    private static String normalizeOptionalAbsoluteUrl(String rawUrl, String baseUrl) {
        String value = trim(rawUrl);
        if (value.isEmpty()) {
            return "";
        }
        String normalized = normalizeContentUrl(value, baseUrl);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        if (value.startsWith("/")) {
            String siteRoot = normalizeSiteRoot(baseUrl);
            if (!siteRoot.isEmpty()) {
                return normalizeContentUrl(siteRoot + value, "");
            }
        }
        return "";
    }

    static String normalizeSiteRoot(String rawUrl) {
        String normalized = normalizeContentUrl(rawUrl, "");
        if (normalized.isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = firstNonBlank(uri.getScheme(), "https").toLowerCase(Locale.ROOT);
            String host = firstNonBlank(uri.getHost(), "").toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                return "";
            }
            int port = uri.getPort();
            boolean usePort = port > 0 && !(scheme.equals("http") && port == 80) && !(scheme.equals("https") && port == 443);
            return scheme + "://" + host + (usePort ? ":" + port : "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String deriveOwner(List<AstraHubContentCollectionService.CollectedContent> contents) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (AstraHubContentCollectionService.CollectedContent content : contents) {
            String owner = trim(content.author());
            if (!owner.isEmpty()) {
                counter.put(owner, counter.getOrDefault(owner, 0) + 1);
            }
        }
        return counter.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }

    private static String deriveLanguage(List<AstraHubContentCollectionService.CollectedContent> contents) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (AstraHubContentCollectionService.CollectedContent content : contents) {
            String language = trim(content.language());
            if (language.isEmpty()) {
                language = AstraHubContentCollectionService.detectLanguage(content.title(), content.summary(), content.rawContent());
            }
            if (!language.isEmpty()) {
                counter.put(language, counter.getOrDefault(language, 0) + 1);
            }
        }
        return counter.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }

    private static Map<String, Object> cloneMap(Map<String, Object> input) {
        return input == null || input.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
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

    private static String pluginVersion() {
        return Optional.ofNullable(AstraHubGraphTransformService.class.getPackage().getImplementationVersion()).orElse("");
    }

    private static String normalizeSyncReason(String value) {
        String normalized = trim(value).toLowerCase(Locale.ROOT);
        normalized = normalized.replace('-', '_').replace('.', '_').replace(' ', '_');
        return normalized;
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String normalizeDateTime(String input, String fallback) {
        String value = trim(input);
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception ignore) {
        }
        try {
            return Instant.parse(value).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception ignore) {
        }
        return fallback;
    }

    private static String maxInstant(String left, String right) {
        try {
            OffsetDateTime a = left == null || left.isBlank() ? null : OffsetDateTime.parse(left);
            OffsetDateTime b = right == null || right.isBlank() ? null : OffsetDateTime.parse(right);
            if (a == null) {
                return b == null ? "" : b.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            }
            if (b == null) {
                return a.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            }
            return (a.isAfter(b) ? a : b).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception ignored) {
            return firstNonBlank(left, right);
        }
    }

    private static boolean readBoolean(JsonNode node, String field, boolean fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    private static String readString(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        String text = value == null || value.isNull() ? "" : trim(value.asText(""));
        return text.isEmpty() ? fallback : text;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> dedupeStrings(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : safeList(values)) {
            String trimmed = trim(value);
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }

    private static List<String> mergeGraphTopics(List<String> topics, String sourceCategory, List<String> series) {
        List<String> merged = new ArrayList<>(safeList(topics));
        if (!trim(sourceCategory).isEmpty()) {
            merged.add(sourceCategory);
        }
        merged.addAll(safeList(series));
        return dedupeStrings(merged);
    }

    private static String sanitizeEmailText(String value) {
        String text = trim(value);
        return text.isEmpty() ? "" : EMAIL_PATTERN.matcher(text).replaceAll("[redacted]");
    }

    private static String sanitizeExternalId(String value) {
        String text = trim(value);
        if (text.isEmpty()) {
            return "site";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int idx = 0; idx < text.length(); idx++) {
            char ch = text.charAt(idx);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == ':' || ch == '.') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private static String firstNonBlank(String... values) {
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

    public record GraphPayload(String version, GraphSource source, String snapshotAt, GraphConsent consent, List<GraphGroup> groups, List<GraphContent> contents) {}
    public record GraphSource(String platform, String plugin, String pluginVersion, String siteId, String siteName, String siteUrl, String nodeName, String category, String nodeAvatar, String siteRssUrl, String syncReason, String owner, String language) {}
    public record GraphConsent(boolean granted, String version, String grantedAt) {}
    public record GraphGroup(String externalId, String name, int priority, Map<String, Object> meta) {}
    public record GraphContent(String externalId, String canonicalUrl, String title, String summary, String cover, String author, List<String> tags, List<String> topics, String sourceCategory, List<String> series, String createdAt, String updatedAt, String publishedAt, String status, String visibility, String language, int wordCount, List<String> groupExternalIds, List<GraphOutboundLink> outboundLinks, List<GraphMentionedSite> mentionedSites, List<String> relatedContentExternalIds, Map<String, Object> meta) {}
    public record GraphOutboundLink(String url, String anchorText, String rel, String linkType) {}
    public record GraphMentionedSite(String siteUrl, String siteName, double confidence) {}
    private record FriendSiteRef(String exactUrl, String siteRoot, String siteName) {}
    private record RelationExtraction(List<GraphOutboundLink> outboundLinks, List<GraphMentionedSite> mentionedSites, List<String> relatedContentExternalIds) {}
}
