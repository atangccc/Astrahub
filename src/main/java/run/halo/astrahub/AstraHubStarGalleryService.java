package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubStarGalleryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BIO = "行走在数字荒原的观测者，试图用文字与代码在万物互联的宇宙里锚定一片宁静星域。专注于前端美学、极简主义设计与去中心化星链网络探索。";

    private static final String PUBLIC_BASE = "/apis/anonymous.astrahub.halo.run/v1alpha1/star-gallery";
    private static final int DEFAULT_PLANET_PAGE_SIZE = 24;
    private static final int MAX_PLANET_PAGE_SIZE = 48;
    private static final Duration REALTIME_REFRESH_DEBOUNCE = Duration.ofMillis(800);

    private final ReactiveSettingFetcher settingFetcher;
    private final AstraHubCredentialReader credentialReader;
    private final AstraHubSignedPlanetReadService signedPlanetReadService;
    private final AstraHubReportOrchestratorService orchestratorService;
    private final HubRealtimeBridge hubRealtimeBridge;

    private final AtomicReference<StarGallerySnapshot> cache = new AtomicReference<>();
    private final AtomicReference<Map<String, List<StarPlanet>>> sectorPlanets = new AtomicReference<>(Map.of());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final Sinks.Many<StarGallerySnapshot> sink = Sinks.many().multicast().directBestEffort();
    private Disposable statusSubscription;
    private Disposable realtimeSubscription;

    @PostConstruct
    public void start() {
        refresh().subscribe();
        statusSubscription = orchestratorService.statusStream()
            .flatMap(ignored -> refresh())
            .subscribe(
                ignored -> {
                },
                error -> log.debug("[AstraHub] star gallery refresh stream stopped", error)
            );
        realtimeSubscription = hubRealtimeBridge.starGalleryEvents()
            .bufferTimeout(256, REALTIME_REFRESH_DEBOUNCE)
            .filter(events -> !events.isEmpty())
            .flatMap(ignored -> refresh())
            .subscribe(
                ignored -> {
                },
                error -> log.debug("[AstraHub] star gallery realtime refresh stream stopped", error)
            );
    }

    @PreDestroy
    public void destroy() {
        if (statusSubscription != null && !statusSubscription.isDisposed()) {
            statusSubscription.dispose();
        }
        if (realtimeSubscription != null && !realtimeSubscription.isDisposed()) {
            realtimeSubscription.dispose();
        }
        sink.tryEmitComplete();
    }

    public Mono<StarGallerySnapshot> current() {
        StarGallerySnapshot snapshot = cache.get();
        return Mono.just(snapshot == null ? fallback() : snapshot);
    }

    public Flux<StarGallerySnapshot> stream() {
        return Flux.concat(current().flux(), sink.asFlux()).distinctUntilChanged();
    }

    public Mono<PlanetPage> planets(String sectorKey, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(MAX_PLANET_PAGE_SIZE, Math.max(1, pageSize <= 0 ? DEFAULT_PLANET_PAGE_SIZE : pageSize));
        List<StarPlanet> all = sectorPlanets.get().getOrDefault(safe(sectorKey), List.of());
        int from = Math.min(all.size(), (safePage - 1) * safePageSize);
        int to = Math.min(all.size(), from + safePageSize);
        return Mono.just(new PlanetPage(all.subList(from, to), safePage, safePageSize, all.size(), to < all.size()));
    }

    public Mono<StarGallerySnapshot> refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return currentOrFallback();
        }
        return Mono.zip(readSetting("connection"), credentialReader.readCredentials(), readSetting("starGallery"))
            .flatMap(settings -> Mono.zip(
                Mono.just(settings.getT1()),
                Mono.just(settings.getT2()),
                fetchOwnSite(settings.getT2()),
                fetchStarGallery(),
                fetchBroadcasts(settings.getT3()).onErrorResume(error -> Mono.just(
                    AstraHubSignedPlanetReadService.SignedReadResult.failed(500, error.getMessage())
                ))
            ))
            .map(tuple -> buildSnapshot(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), tuple.getT5()))
            .doOnNext(snapshot -> {
                cache.set(snapshot);
                sink.tryEmitNext(snapshot);
            })
            .onErrorResume(error -> {
                log.debug("[AstraHub] build star gallery snapshot failed", error);
                return currentOrFallback();
            })
            .doFinally(signal -> refreshing.set(false));
    }

    private Mono<StarGallerySnapshot> currentOrFallback() {
        StarGallerySnapshot snapshot = cache.get();
        return Mono.just(snapshot == null ? fallback() : snapshot);
    }

    private StarGallerySnapshot buildSnapshot(JsonNode connection,
                                              JsonNode credentials,
                                              AstraHubSignedPlanetReadService.SignedReadResult ownSiteResult,
                                              AstraHubSignedPlanetReadService.SignedReadResult starGalleryResult,
                                              AstraHubSignedPlanetReadService.SignedReadResult feedResult) {
        String siteName = readString(connection, "siteName", "");
        String siteUrl = readString(connection, "siteUrl", "/");
        String configuredDescription = readString(connection, "siteDescription", "");
        String nodeName = readString(connection, "siteNodeName", siteName);
        String nodeAvatar = readString(connection, "siteNodeAvatar", "");
        String hubBaseUrl = normalizeHubBaseUrl(readString(connection, "hubBaseUrl", ""));
        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        AstraHubReportOrchestratorService.RuntimeStatus status = orchestratorService.runtimeStatus();
        JsonNode ownSite = parseBody(ownSiteResult);
        JsonNode ownSummary = ownSite.path("summary");
        JsonNode starGallery = parseBody(starGalleryResult);

        Map<String, List<StarPlanet>> nextSectorPlanets = new HashMap<>();
        List<StarSector> sectors = parseSectors(starGallery, hubBaseUrl, nextSectorPlanets);
        List<StarPost> posts = parsePosts(feedResult, hubBaseUrl);
        int relationCount = readInt(starGallery, "relationCount", sectors.size());
        String resolvedNodeName = firstNonBlank(text(ownSummary, "nodeName"), nodeName);
        String resolvedSiteName = firstNonBlank(text(starGallery, "siteName"), text(ownSummary, "name"), siteName);
        String resolvedAvatar = firstNonBlank(text(ownSummary, "avatar"), nodeAvatar);
        String ownNodeId = firstNonBlank(text(ownSummary, "nodeId"), resolvedNodeName);
        String resolvedDescription = firstNonBlank(
            text(starGallery, "siteDescription"),
            text(starGallery, "description"),
            text(ownSummary, "description"),
            configuredDescription,
            DEFAULT_BIO
        );

        StarProfile profile = new StarProfile(
            resolvedSiteName,
            resolvedAvatar,
            firstNonBlank(text(starGallery, "siteUrl"), text(ownSummary, "url"), siteUrl),
            resolvedDescription,
            resolvedNodeName,
            resolvedSiteName,
            buildOrbitUrl(hubBaseUrl, ownNodeId),
            firstNonBlank(text(starGallery, "generatedAt"), status.lastSuccessfulPushAt(), status.updatedAt(), nowIso()),
            siteId.isBlank() ? "Slot: -" : "Slot: " + siteId,
            status.connected() ? "verified" : "pending",
            status.connected() ? "online" : "offline",
            firstNonBlank(status.lastSuccessfulPushAt(), status.updatedAt(), nowIso()),
            maskSecret(apiKey),
            relationCount
        );

        sectorPlanets.set(copyPlanetMap(nextSectorPlanets));
        return new StarGallerySnapshot(true, nowIso(), profile, sectors, posts, relationCount);
    }

    private List<StarSector> parseSectors(JsonNode starGallery,
                                          String hubBaseUrl,
                                          Map<String, List<StarPlanet>> sectorPlanets) {
        JsonNode sectors = starGallery.path("sectors");
        if (!sectors.isArray() || sectors.isEmpty()) {
            return List.of();
        }
        return readArray(sectors).stream()
            .map(item -> toSector(item, hubBaseUrl, sectorPlanets))
            .toList();
    }

    private StarSector toSector(JsonNode sector,
                                String hubBaseUrl,
                                Map<String, List<StarPlanet>> sectorPlanets) {
        String name = text(sector, "name");
        String nodeId = text(sector, "nodeId");
        String description = text(sector, "description");
        String sectorKey = stableKey("sector", firstNonBlank(nodeId, name));
        List<StarPlanet> planets = parsePlanets(sector.path("planets"), hubBaseUrl);
        sectorPlanets.put(sectorKey, planets);
        return new StarSector(
            name,
            resolveHubAsset(text(sector, "avatar"), hubBaseUrl),
            description,
            readDouble(sector, "influence", 0),
            readDouble(sector, "trust", 0),
            readInt(sector, "friendCount", 0),
            "",
            resolveHubAsset(text(sector, "url"), hubBaseUrl),
            nodeId,
            readInt(sector, "relationCount", 0),
            text(sector, "joinedAt"),
            text(sector, "activeAt"),
            PUBLIC_BASE + "/sectors/" + sectorKey + "/planets",
            List.of()
        );
    }

    private List<StarPlanet> parsePlanets(JsonNode planets,
                                          String hubBaseUrl) {
        if (!planets.isArray() || planets.isEmpty()) {
            return List.of();
        }
        return readArray(planets).stream()
            .map(item -> {
                String id = text(item, "id");
                String name = text(item, "name");
                return new StarPlanet(
                    id,
                    name,
                    resolveHubAsset(text(item, "avatar"), hubBaseUrl),
                    "",
                    text(item, "description")
                );
            })
            .filter(item -> !item.name().isBlank())
            .toList();
    }

    private static List<JsonNode> readArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull() && !item.isMissingNode()) {
                values.add(item);
            }
        }
        return List.copyOf(values);
    }

    private Mono<AstraHubSignedPlanetReadService.SignedReadResult> fetchStarGallery() {
        return signedPlanetReadService.fetch("/v1/theme/star-gallery", Map.of())
            .onErrorResume(error -> Mono.just(AstraHubSignedPlanetReadService.SignedReadResult.failed(500, error.getMessage())));
    }

    private Mono<AstraHubSignedPlanetReadService.SignedReadResult> fetchBroadcasts(JsonNode starGallerySetting) {
        int limit = Math.min(120, Math.max(6, readInt(starGallerySetting, "timelineLimit", 48)));
        return signedPlanetReadService.fetch("/v1/planet/broadcasts", Map.of("limit", String.valueOf(limit), "hours", "168"))
            .onErrorResume(error -> Mono.just(AstraHubSignedPlanetReadService.SignedReadResult.failed(500, error.getMessage())));
    }

    private Mono<AstraHubSignedPlanetReadService.SignedReadResult> fetchOwnSite(JsonNode credentials) {
        String siteId = readString(credentials, "siteId", "");
        if (siteId.isBlank()) {
            return Mono.just(AstraHubSignedPlanetReadService.SignedReadResult.failed(400, "siteId is required"));
        }
        return signedPlanetReadService.fetch("/v1/graph/sites/" + siteId, Map.of("size", "1"))
            .onErrorResume(error -> Mono.just(AstraHubSignedPlanetReadService.SignedReadResult.failed(500, error.getMessage())));
    }

    private JsonNode parseBody(AstraHubSignedPlanetReadService.SignedReadResult result) {
        if (result == null || !result.success() || result.body().isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(result.body());
        } catch (Exception error) {
            log.debug("[AstraHub] parse star gallery body failed", error);
            return MAPPER.createObjectNode();
        }
    }

    private List<StarPost> parsePosts(AstraHubSignedPlanetReadService.SignedReadResult result, String hubBaseUrl) {
        if (result == null || !result.success() || result.body().isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(result.body());
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                return List.of();
            }
            List<StarPost> posts = new ArrayList<>();
            for (JsonNode item : items) {
                String title = firstNonBlank(text(item, "title"), text(item, "message"), text(item, "source"));
                String url = text(item, "url");
                if (title.isBlank()) {
                    continue;
                }
                String type = text(item, "type");
                String description = firstNonBlank(text(item, "message"), text(item, "description"));
                if ("rss".equalsIgnoreCase(type)) {
                    description = firstNonBlank(text(item, "summary"), description);
                }
                posts.add(new StarPost(
                    title,
                    description,
                    url,
                    text(item, "siteUrl"),
                    firstNonBlank(text(item, "time"), text(item, "updatedAt"), text(item, "publishedAt"), text(root, "generatedAt")),
                    resolveHubAsset(text(item, "avatar"), hubBaseUrl),
                    firstNonBlank(text(item, "nodeName"), text(item, "siteName")),
                    text(item, "siteName"),
                    text(item, "source"),
                    type,
                    readTags(item.path("tags"))
                ));
            }
            return List.copyOf(posts);
        } catch (Exception error) {
            log.debug("[AstraHub] parse star gallery posts failed", error);
            return List.of();
        }
    }

    private Mono<JsonNode> readSetting(String key) {
        return settingFetcher.getSettingValue(key)
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .onErrorResume(error -> Mono.empty())
            .switchIfEmpty(Mono.fromSupplier(MAPPER::createObjectNode));
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

    private static List<String> readTags(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode item : node) {
            String value = safe(item.asText("")).trim();
            if (!value.isBlank()) {
                tags.add(value);
            }
        }
        return List.copyOf(tags);
    }

    private static String readString(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value.isBlank() ? fallback : value;
    }

    private static int readInt(JsonNode node, String field, int fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    private static double readDouble(JsonNode node, String field, double fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asDouble(fallback);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : safe(value.asText("")).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String safe = safe(value).trim();
            if (!safe.isBlank()) {
                return safe;
            }
        }
        return "";
    }

    private static String resolveHubAsset(String value, String hubBaseUrl) {
        String raw = safe(value).trim();
        if (raw.isBlank() || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) {
            return raw;
        }
        String base = safe(hubBaseUrl).trim();
        if (base.isBlank() || !raw.startsWith("/")) {
            return raw;
        }
        return base + raw;
    }

    private static Map<String, List<StarPlanet>> copyPlanetMap(Map<String, List<StarPlanet>> source) {
        Map<String, List<StarPlanet>> copy = new HashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value == null ? List.of() : value)));
        return Map.copyOf(copy);
    }

    private static String stableKey(String prefix, String value) {
        String raw = safe(value).trim();
        if (raw.isBlank()) {
            raw = prefix + "-" + nowIso();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return prefix + "_" + HexFormat.of().formatHex(digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception ignored) {
            return prefix + "_" + Integer.toHexString(raw.hashCode());
        }
    }

    private static String normalizeHubBaseUrl(String value) {
        String raw = safe(value).trim();
        if (raw.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(raw);
            String scheme = safe(uri.getScheme()).toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return "";
            }
            return raw.replaceAll("/+$", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String buildOrbitUrl(String hubBaseUrl, String nodeId) {
        String base = normalizeHubBaseUrl(hubBaseUrl);
        String id = safe(nodeId).trim();
        if (base.isBlank() || id.isBlank()) {
            return "";
        }
        return base + "/?tab=orbit&orbitNodeId=" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "&orbitPanel=detail";
    }

    private static String maskSecret(String value) {
        String raw = safe(value).trim();
        if (raw.isBlank()) {
            return "-";
        }
        if (raw.length() <= 10) {
            return raw.charAt(0) + "***" + raw.charAt(raw.length() - 1);
        }
        return raw.substring(0, 6) + "..." + raw.substring(raw.length() - 4);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static StarGallerySnapshot fallback() {
        return new StarGallerySnapshot(
            false,
            nowIso(),
            new StarProfile("", "", "/", "", "", "", "", nowIso(), "Slot: -", "pending", "offline", "", "-", 0),
            List.of(),
            List.of(),
            0
        );
    }

    public record StarGallerySnapshot(
        boolean available,
        String generatedAt,
        StarProfile profile,
        List<StarSector> sectors,
        List<StarPost> posts,
        int relationCount
    ) {
    }

    public record StarProfile(
        String siteName,
        String avatarUrl,
        String siteUrl,
        String bio,
        String constellation,
        String nodeSlug,
        String orbitUrl,
        String routeCoordinate,
        String planetSlot,
        String rssStatus,
        String onlineStatus,
        String lastSyncTime,
        String publicKeyMasked,
        int relationCount
    ) {
    }

    public record StarSector(
        String name,
        String avatar,
        String description,
        double influence,
        double trust,
        int friendCount,
        String syncTime,
        String url,
        String nodeId,
        int relationCount,
        String joinedAt,
        String activeAt,
        String planetsUrl,
        List<StarPlanet> planets
    ) {
        public StarSector {
            planets = List.copyOf(planets == null ? List.of() : planets);
        }
    }

    public record StarPlanet(
        String id,
        String name,
        String avatar,
        String url,
        String description
    ) {
    }

    public record PlanetPage(
        List<StarPlanet> items,
        int page,
        int pageSize,
        int total,
        boolean hasMore
    ) {
    }

    public record StarPost(
        String title,
        String description,
        String url,
        String siteUrl,
        String publishTime,
        String avatar,
        String nodeName,
        String siteName,
        String source,
        String type,
        List<String> tags
    ) {
    }

}
