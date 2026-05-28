package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubContentCollectionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final int PAGE_SIZE = 200;
    private static final String POSTS_API = "/apis/api.content.halo.run/v1alpha1/posts";

    private final ReactiveSettingFetcher settingFetcher;

    public Mono<CollectedContentPayload> collect() {
        return collect(null);
    }

    public Mono<CollectedContentPayload> collect(ServerRequest request) {
        return collectInternal(null, request);
    }

    public Mono<CollectedContentPayload> collectIncremental(String updatedSince) {
        return collectIncremental(updatedSince, null);
    }

    public Mono<CollectedContentPayload> collectIncremental(String updatedSince, ServerRequest request) {
        return collectInternal(parseOffsetDateTime(updatedSince), request);
    }

    private Mono<CollectedContentPayload> collectInternal(OffsetDateTime updatedSince, ServerRequest request) {
        return resolveBaseUrl(request)
            .flatMap(baseUrl -> Mono.fromCallable(() -> collectBlocking(baseUrl, updatedSince))
                .subscribeOn(Schedulers.boundedElastic())
            )
            .onErrorResume(error -> {
                log.warn("[AstraHub] failed to collect public contents", error);
                return Mono.just(new CollectedContentPayload(nowIso(), "", List.of()));
            });
    }

    private Mono<String> resolveBaseUrl(ServerRequest request) {
        return settingFetcher.getSettingValue("connection")
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .map(connection -> normalizeBaseUrl(readString(connection, "siteUrl", "")))
            .onErrorResume(error -> Mono.just(""))
            .map(configured -> configured.isBlank() ? resolveRequestBaseUrl(request) : configured)
            .map(baseUrl -> baseUrl == null ? "" : baseUrl);
    }

    private CollectedContentPayload collectBlocking(String baseUrl, OffsetDateTime updatedSince) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new CollectedContentPayload(nowIso(), "", List.of());
        }

        List<CollectedContent> contents = new ArrayList<>();
        contents.addAll(fetchContents(baseUrl, POSTS_API, "post"));
        if (updatedSince != null) {
            contents.removeIf(content -> !wasUpdatedSince(content, updatedSince));
        }
        contents.sort((left, right) -> right.publishedAt().compareTo(left.publishedAt()));

        return new CollectedContentPayload(nowIso(), baseUrl, contents);
    }

    private List<CollectedContent> fetchContents(String baseUrl, String path, String kind) {
        List<CollectedContent> contents = new ArrayList<>();
        int page = 0;
        int totalPages = Integer.MAX_VALUE;

        while (page < totalPages) {
            JsonNode pageJson = fetchJson(buildPagedEndpoint(baseUrl, path, page));
            JsonNode items = pageJson.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }

            totalPages = Math.max(1, pageJson.path("totalPages").asInt(page + 1));
            for (JsonNode item : items) {
                String name = trim(item.path("metadata").path("name").asText(""));
                if (name.isEmpty()) {
                    continue;
                }

                JsonNode detail = fetchJson(baseUrl + path + "/" + urlEncode(name));
                CollectedContent content = parseContent(kind, detail);
                if (content != null) {
                    contents.add(content);
                }
            }

            if (items.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }

        return contents;
    }

    private static String buildPagedEndpoint(String baseUrl, String path, int page) {
        return baseUrl + path + "?page=" + page + "&size=" + PAGE_SIZE
            + "&sort=metadata.creationTimestamp,desc";
    }

    private JsonNode fetchJson(String endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return MAPPER.createObjectNode();
            }

            String body = Optional.ofNullable(response.body()).orElse("");
            if (body.isBlank()) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(body);
        } catch (Exception error) {
            log.debug("[AstraHub] fetch json failed: {} -> {}", endpoint, error.getMessage());
            return MAPPER.createObjectNode();
        }
    }

    private CollectedContent parseContent(String kind, JsonNode root) {
        String externalId = trim(root.path("metadata").path("name").asText(""));
        String canonicalUrl = trim(root.path("status").path("permalink").asText(""));
        if (externalId.isEmpty() || canonicalUrl.isEmpty()) {
            return null;
        }

        JsonNode spec = root.path("spec");
        JsonNode status = root.path("status");
        JsonNode content = root.path("content");

        List<String> tagNames = new ArrayList<>();
        for (JsonNode tag : safeArray(root.path("tags"))) {
            String tagName = firstNonBlank(
                trim(tag.path("spec").path("displayName").asText("")),
                trim(tag.path("metadata").path("name").asText(""))
            );
            if (!tagName.isEmpty()) {
                tagNames.add(tagName);
            }
        }

        List<GroupReference> groups = new ArrayList<>(extractCategoryGroups(root));
        List<GroupReference> seriesGroups = extractSeriesGroups(root, spec, status);
        groups.addAll(seriesGroups);
        String sourceCategory = groups.stream()
            .filter(group -> "content_category".equals(group.type()))
            .map(GroupReference::name)
            .findFirst()
            .orElse("");
        List<String> seriesNames = seriesGroups.stream()
            .map(GroupReference::name)
            .toList();

        Set<String> topicSet = new LinkedHashSet<>();
        groups.stream().map(GroupReference::name).forEach(topicSet::add);
        tagNames.forEach(topicSet::add);

        String rawContent = trim(content.path("raw").asText(""));
        String htmlContent = trim(content.path("content").asText(""));
        String title = trim(spec.path("title").asText(""));
        String summary = firstNonBlank(
            trim(spec.path("excerpt").path("raw").asText("")),
            trim(status.path("excerpt").asText("")),
            summarize(firstNonBlank(rawContent, htmlContent), 260)
        );
        String author = firstNonBlank(
            trim(root.path("owner").path("displayName").asText("")),
            trim(root.path("owner").path("name").asText("")),
            trim(spec.path("owner").asText(""))
        );
        String createdAt = normalizeDateTime(
            trim(root.path("metadata").path("creationTimestamp").asText("")),
            nowIso()
        );
        String updatedAt = normalizeDateTime(
            trim(status.path("lastModifyTime").asText("")),
            createdAt
        );
        String publishedAt = normalizeDateTime(
            trim(spec.path("publishTime").asText("")),
            updatedAt
        );
        String visibility = mapVisibility(trim(spec.path("visible").asText("PUBLIC")));
        String language = detectLanguage(title, summary, rawContent);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("contentKind", kind);
        meta.put("slug", trim(spec.path("slug").asText("")));
        if (!sourceCategory.isEmpty()) {
            meta.put("sourceCategory", sourceCategory);
        }
        if (!seriesNames.isEmpty()) {
            meta.put("series", List.copyOf(seriesNames));
        }

        return new CollectedContent(
            externalId,
            kind,
            canonicalUrl,
            title,
            summary,
            trim(spec.path("cover").asText("")),
            author,
            List.copyOf(tagNames),
            List.copyOf(topicSet),
            List.copyOf(groups),
            sourceCategory,
            List.copyOf(seriesNames),
            createdAt,
            updatedAt,
            publishedAt,
            "published",
            visibility,
            language,
            estimateWordCount(firstNonBlank(rawContent, htmlContent)),
            rawContent,
            htmlContent,
            meta
        );
    }

    private static List<GroupReference> extractCategoryGroups(JsonNode root) {
        List<GroupReference> groups = new ArrayList<>();
        for (JsonNode category : safeArray(root.path("categories"))) {
            String categoryId = trim(category.path("metadata").path("name").asText(""));
            String categoryName = firstNonBlank(
                trim(category.path("spec").path("displayName").asText("")),
                categoryId
            );
            if (categoryId.isEmpty() || categoryName.isEmpty()) {
                continue;
            }
            groups.add(new GroupReference(
                "category:" + categoryId,
                categoryName,
                category.path("spec").path("priority").asInt(0),
                "content_category"
            ));
        }
        return groups;
    }

    private static List<GroupReference> extractSeriesGroups(JsonNode root, JsonNode spec, JsonNode status) {
        LinkedHashMap<String, GroupReference> groups = new LinkedHashMap<>();
        appendStructuredGroups(groups, root.path("series"), "series", "content_series");
        appendStructuredGroups(groups, spec.path("series"), "series", "content_series");
        appendStructuredGroups(groups, status.path("series"), "series", "content_series");
        appendSeriesFromMap(groups, root.path("metadata").path("annotations"));
        appendSeriesFromMap(groups, root.path("metadata").path("labels"));
        appendSeriesFromMap(groups, spec.path("annotations"));
        appendSeriesFromMap(groups, spec.path("labels"));
        appendSeriesFromMap(groups, status.path("annotations"));
        appendSeriesFromMap(groups, status.path("labels"));
        return new ArrayList<>(groups.values());
    }

    private static void appendStructuredGroups(
        Map<String, GroupReference> groups,
        JsonNode node,
        String prefix,
        String type
    ) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                appendStructuredGroups(groups, item, prefix, type);
            }
            return;
        }
        if (node.isObject()) {
            JsonNode items = node.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    appendStructuredGroups(groups, item, prefix, type);
                }
                return;
            }
            String groupName = firstNonBlank(
                trim(node.path("spec").path("displayName").asText("")),
                trim(node.path("displayName").asText("")),
                trim(node.path("title").asText("")),
                trim(node.path("name").asText("")),
                trim(node.path("metadata").path("name").asText(""))
            );
            String explicitID = firstNonBlank(
                trim(node.path("metadata").path("name").asText("")),
                trim(node.path("id").asText(""))
            );
            int priority = node.path("spec").path("priority").asInt(node.path("priority").asInt(0));
            appendGroupReference(groups, prefix, explicitID, groupName, priority, type);
            return;
        }
        if (node.isTextual()) {
            appendDelimitedGroups(groups, node.asText(""), prefix, type);
        }
    }

    private static void appendSeriesFromMap(Map<String, GroupReference> groups, JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.properties().forEach(entry -> {
            if (!looksLikeSeriesKey(entry.getKey())) {
                return;
            }
            appendDelimitedGroups(groups, entry.getValue().asText(""), "series", "content_series");
        });
    }

    private static void appendDelimitedGroups(
        Map<String, GroupReference> groups,
        String rawValues,
        String prefix,
        String type
    ) {
        String value = trim(rawValues);
        if (value.isEmpty()) {
            return;
        }
        for (String part : value.split("[,|;/]")) {
            appendGroupReference(groups, prefix, "", trim(part), 0, type);
        }
    }

    private static void appendGroupReference(
        Map<String, GroupReference> groups,
        String prefix,
        String explicitID,
        String displayName,
        int priority,
        String type
    ) {
        String groupName = trim(displayName);
        if (groupName.isEmpty()) {
            return;
        }
        String normalizedID = firstNonBlank(
            normalizeGroupExternalId(prefix, explicitID),
            normalizeGroupExternalId(prefix, groupName)
        );
        if (normalizedID.isEmpty()) {
            return;
        }
        groups.putIfAbsent(normalizedID, new GroupReference(normalizedID, groupName, priority, type));
    }

    private static boolean looksLikeSeriesKey(String rawKey) {
        String key = trim(rawKey).toLowerCase(Locale.ROOT);
        return key.equals("series")
            || key.endsWith("/series")
            || key.endsWith(".series")
            || key.contains("series-name")
            || key.contains("series_name");
    }

    private static String normalizeGroupExternalId(String prefix, String rawValue) {
        String value = trim(rawValue);
        if (value.isEmpty()) {
            return "";
        }
        String slug = value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{Nd}]+", "-")
            .replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            slug = value.replaceAll("\\s+", "-");
        }
        return slug.isEmpty() ? "" : prefix + ":" + slug;
    }

    private static String mapVisibility(String raw) {
        if ("PRIVATE".equalsIgnoreCase(raw)) {
            return "private";
        }
        if ("INTERNAL".equalsIgnoreCase(raw)) {
            return "unlisted";
        }
        return "public";
    }

    private static boolean wasUpdatedSince(CollectedContent content, OffsetDateTime watermark) {
        if (watermark == null) {
            return true;
        }
        OffsetDateTime updatedAt = firstParsedOffsetDateTime(
            content.updatedAt(),
            content.publishedAt(),
            content.createdAt()
        );
        return updatedAt != null && !updatedAt.isBefore(watermark);
    }

    private static OffsetDateTime firstParsedOffsetDateTime(String... values) {
        for (String value : values) {
            OffsetDateTime parsed = parseOffsetDateTime(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static int estimateWordCount(String raw) {
        String plain = stripMarkup(raw);
        if (plain.isBlank()) {
            return 0;
        }

        int eastAsianChars = 0;
        int latinWords = 0;
        boolean inWord = false;
        for (int i = 0; i < plain.length(); i++) {
            char ch = plain.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(ch);
            if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
                eastAsianChars++;
                inWord = false;
                continue;
            }
            if (Character.isLetterOrDigit(ch)) {
                if (!inWord) {
                    latinWords++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return eastAsianChars + latinWords;
    }

    static String detectLanguage(String... samples) {
        int han = 0;
        int latin = 0;
        for (String sample : samples) {
            if (sample == null) {
                continue;
            }
            for (int i = 0; i < sample.length(); i++) {
                char ch = sample.charAt(i);
                Character.UnicodeScript script = Character.UnicodeScript.of(ch);
                if (script == Character.UnicodeScript.HAN) {
                    han++;
                } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                    latin++;
                }
            }
        }
        if (han >= 10 && han >= latin / 2) {
            return "zh-CN";
        }
        if (latin >= 20) {
            return "en-US";
        }
        return "";
    }

    static String summarize(String raw, int max) {
        String plain = stripMarkup(raw).replaceAll("\\s+", " ").trim();
        if (plain.length() <= max) {
            return plain;
        }
        return plain.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    static String stripMarkup(String raw) {
        String text = trim(raw);
        if (text.isEmpty()) {
            return "";
        }
        return text
            .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
            .replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1 ")
            .replaceAll("<[^>]+>", " ")
            .replaceAll("[*_`>#-]", " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&");
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

    private static String resolveRequestBaseUrl(ServerRequest request) {
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
        return normalizeBaseUrl(proto + "://" + host);
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

    private static String normalizeBaseUrl(String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
            return uri.toString();
        } catch (Exception ignored) {
            return "";
        }
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
        return fallback;
    }

    private static List<JsonNode> safeArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        node.forEach(items::add);
        return items;
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trim(value);
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static OffsetDateTime parseOffsetDateTime(String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record CollectedContentPayload(
        String collectedAt,
        String baseUrl,
        List<CollectedContent> contents
    ) {
    }

    public record CollectedContent(
        String externalId,
        String kind,
        String canonicalUrl,
        String title,
        String summary,
        String cover,
        String author,
        List<String> tags,
        List<String> topics,
        List<GroupReference> groups,
        String sourceCategory,
        List<String> series,
        String createdAt,
        String updatedAt,
        String publishedAt,
        String status,
        String visibility,
        String language,
        int wordCount,
        String rawContent,
        String htmlContent,
        Map<String, Object> meta
    ) {
        public CollectedContent(
            String externalId,
            String kind,
            String canonicalUrl,
            String title,
            String summary,
            String cover,
            String author,
            List<String> tags,
            List<String> topics,
            List<GroupReference> groups,
            String createdAt,
            String updatedAt,
            String publishedAt,
            String status,
            String visibility,
            String language,
            int wordCount,
            String rawContent,
            String htmlContent,
            Map<String, Object> meta
        ) {
            this(
                externalId,
                kind,
                canonicalUrl,
                title,
                summary,
                cover,
                author,
                tags,
                topics,
                groups,
                "",
                List.of(),
                createdAt,
                updatedAt,
                publishedAt,
                status,
                visibility,
                language,
                wordCount,
                rawContent,
                htmlContent,
                meta
            );
        }
    }

    public record GroupReference(
        String externalId,
        String name,
        int priority,
        String type
    ) {
    }
}
