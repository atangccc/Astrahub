package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class HubRealtimeBridge {

    static final String HUB_MASCOT_BUBBLE_TYPE = "mascot_bubble";
    static final String HUB_MASCOT_ARTICLE_CARD_TYPE = "mascot_article_card";
    static final String HUB_FRIEND_RELATION_REMOVED_TYPE = "friend_relation_removed";
    static final String HUB_SITE_PROFILE_UPDATED_TYPE = "site_profile_updated";
    static final String HUB_PLANET_BROADCAST_TYPE = "planet_broadcast";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private static final String HUB_WS_PATH = "/v1/ws";
    private static final Duration INITIAL_CONNECT_DELAY = Duration.ofSeconds(5);
    private static final Duration MIN_RECONNECT_DELAY = Duration.ofSeconds(3);
    private static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(60);
    private static final int RECENT_EVENT_ID_LIMIT = 256;
    private static final Set<String> STAR_GALLERY_EVENT_TYPES = Set.of(
        "ws_ready",
        "site_registered",
        "site_restored",
        "graph_pushed",
        "site_relation_updated",
        "friend_relation_removed",
        "site_profile_updated",
        "friend_invitation_created",
        "friend_invitation_reviewed",
        "friend_invitation_acked",
        "friend_invitation_cancelled",
        "friend_invitation_deleted",
        "world_chat_message_created",
        HUB_MASCOT_ARTICLE_CARD_TYPE,
        HUB_PLANET_BROADCAST_TYPE
    );

    private final ReactiveSettingFetcher settingFetcher;
    private final AstraHubFriendManagementService friendManagementService;
    private final AstraHubFriendLinkReconcileService friendLinkReconcileService;
    private final AstraHubCredentialReader credentialReader;

    private final Scheduler bridgeScheduler = Schedulers.newSingle("astrahub-hub-ws");
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicReference<WebSocket> currentWebSocket = new AtomicReference<>();
    private final AtomicReference<Disposable> reconnectTask = new AtomicReference<>();
    private final AtomicReference<String> lastEventId = new AtomicReference<>("");
    private final AtomicReference<HubRealtimeBridgeStatus> currentStatus = new AtomicReference<>(
        new HubRealtimeBridgeStatus(false, "INIT", "bridge not started", "", "", nowIso())
    );
    private final RecentEventIds recentEventIds = new RecentEventIds(RECENT_EVENT_ID_LIMIT);
    private final Sinks.Many<HubMascotRealtimeEvent> mascotSink =
        Sinks.many().multicast().directBestEffort();
    private final Sinks.Many<StarGalleryRealtimeEvent> starGallerySink =
        Sinks.many().multicast().directBestEffort();

    @PostConstruct
    public void start() {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        updateStatus(false, "STARTING", "bridge starting");
        scheduleReconnect(INITIAL_CONNECT_DELAY);
    }

    @PreDestroy
    public void destroy() {
        active.set(false);
        Disposable task = reconnectTask.getAndSet(null);
        if (task != null && !task.isDisposed()) {
            task.dispose();
        }
        WebSocket webSocket = currentWebSocket.getAndSet(null);
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin shutdown");
            } catch (Exception ignored) {
            }
        }
        mascotSink.tryEmitComplete();
        starGallerySink.tryEmitComplete();
        bridgeScheduler.dispose();
    }

    public Flux<HubMascotRealtimeEvent> mascotStream() {
        return mascotSink.asFlux();
    }

    public Flux<StarGalleryRealtimeEvent> starGalleryEvents() {
        return starGallerySink.asFlux();
    }

    public HubRealtimeBridgeStatus runtimeStatus() {
        return currentStatus.get();
    }

    private void scheduleReconnect(Duration delay) {
        if (!active.get()) {
            return;
        }
        Duration safeDelay = sanitizeDelay(delay);
        Disposable task = Mono.delay(safeDelay, bridgeScheduler)
            .doOnNext(ignored -> connectOnce())
            .subscribe();
        Disposable oldTask = reconnectTask.getAndSet(task);
        if (oldTask != null && !oldTask.isDisposed()) {
            oldTask.dispose();
        }
    }

    private void connectOnce() {
        if (!active.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        Mono.zip(readHubBaseUrl(), friendManagementService.issueRealtimeToken())
            .flatMap(tuple -> {
                AstraHubFriendManagementService.RealtimeTokenResult tokenResult = tuple.getT2();
                if (!tokenResult.success() || safe(tokenResult.token()).isBlank()) {
                    return Mono.error(new IllegalStateException(safeMessage(tokenResult.message(), "issue websocket token failed")));
                }
                URI endpoint = toWebSocketUri(tuple.getT1(), tokenResult.token(), lastEventId.get());
                updateStatus(false, "CONNECTING", "connecting to hub realtime");
                return Mono.fromCompletionStage(HTTP_CLIENT.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(endpoint, new HubWebSocketListener()))
                    .doOnNext(currentWebSocket::set)
                    .then();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .doFinally(signal -> connecting.set(false))
            .subscribe(
                ignored -> {
                },
                error -> {
                    if (!active.get()) {
                        return;
                    }
                    updateStatus(false, "ERROR", safeMessage(error.getMessage(), "hub realtime connect failed"));
                    scheduleReconnect(nextReconnectDelay());
                }
            );
    }

    private Mono<String> readHubBaseUrl() {
        return settingFetcher.getSettingValue("connection")
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .map(node -> normalizeBaseUrl(readString(node, "hubBaseUrl", "")))
            .filter(value -> !value.isBlank())
            .switchIfEmpty(Mono.error(new IllegalStateException("hubBaseUrl is required")))
            .onErrorMap(error -> new IllegalStateException(safeMessage(error.getMessage(), "hubBaseUrl is required")));
    }

    private Duration nextReconnectDelay() {
        int attempt = Math.min(reconnectAttempts.incrementAndGet(), 6);
        long seconds = MIN_RECONNECT_DELAY.getSeconds() * (1L << Math.max(0, attempt - 1));
        return Duration.ofSeconds(Math.min(seconds, MAX_RECONNECT_DELAY.getSeconds()));
    }

    private void handleCloseOrError(String phase, String message) {
        currentWebSocket.set(null);
        if (!active.get()) {
            return;
        }
        updateStatus(false, phase, safeMessage(message, "hub realtime disconnected"));
        scheduleReconnect(nextReconnectDelay());
    }

    private void handleHubMessage(String raw) {
        ParsedHubEvent parsed = parseHubMascotEvent(raw);
        if (parsed.eventId() != null && !parsed.eventId().isBlank()) {
            lastEventId.set(parsed.eventId());
            if (!recentEventIds.add(parsed.eventId())) {
                return;
            }
        }
        StarGalleryRealtimeEvent starGalleryEvent = parseStarGalleryEvent(raw);
        if (starGalleryEvent != null) {
            starGallerySink.tryEmitNext(starGalleryEvent);
        }
        // Self-cleanup events are dispatched separately from mascot streams.
        if (parsed.relationRemoved() != null) {
            handleFriendRelationRemoved(parsed.relationRemoved());
            return;
        }
        // Phase 5: 站点资料变更 → 同步本地友链。
        if (parsed.profileUpdated() != null) {
            handleSiteProfileUpdated(parsed.profileUpdated());
            return;
        }
        if (parsed.event() == null) {
            return;
        }
        mascotSink.tryEmitNext(parsed.event());
    }

    /**
     * Hub 广播某站点资料更新后，把本地指向该站的友链 Link CR 同步成最新本体。
     */
    private void handleSiteProfileUpdated(SiteProfileUpdatedEvent event) {
        if (event == null || safe(event.siteId()).trim().isEmpty()) {
            return;
        }
        friendLinkReconcileService.updateLocalLinkByPeerSiteId(
                event.siteId(), event.name(), event.url(), event.description(), event.nodeAvatar(), event.rssUrl())
            .doOnNext(result -> {
                if (!result.success()) {
                    log.warn("[AstraHub] sync local link by peer site id failed: peerSiteId={}, message={}",
                        event.siteId(), result.message());
                }
            })
            .onErrorResume(error -> {
                log.warn("[AstraHub] handle site_profile_updated failed", error);
                return Mono.empty();
            })
            .subscribe();
    }

    /**
     * Auto-cleans this site's local Halo Link CR when the hub broadcasts that a
     * friend relation involving us has been removed. Idempotent on both sides.
     */
    private void handleFriendRelationRemoved(FriendRelationRemovedEvent event) {
        if (event == null) {
            return;
        }
        credentialReader.readCredentials()
            .map(node -> node == null ? "" : safe(node.path("siteId").asText("")).trim())
            .defaultIfEmpty("")
            .flatMap(currentSiteId -> {
                String resolvedPeerUrl = resolveLocalLinkPeerUrlForSelfCleanup(event, currentSiteId);
                if (resolvedPeerUrl.isBlank()) {
                    return Mono.empty();
                }
                return friendLinkReconcileService.deleteLocalLinkByPeerUrl(resolvedPeerUrl)
                    .doOnNext(result -> {
                        if (!result.success()) {
                            log.warn("[AstraHub] auto cleanup local link by peer url failed: peerUrl={}, message={}",
                                resolvedPeerUrl, result.message());
                        }
                    });
            })
            .onErrorResume(error -> {
                log.warn("[AstraHub] handle friend_relation_removed failed", error);
                return Mono.empty();
            })
            .subscribe();
    }

    /**
     * Returns the peer URL whose local Link CR should be deleted on this site,
     * or empty if this site is neither actor nor peer of the event.
     */
    private static String resolveLocalLinkPeerUrlForSelfCleanup(FriendRelationRemovedEvent event, String currentSiteId) {
        String currentId = safe(currentSiteId).trim();
        if (currentId.isEmpty()) {
            return "";
        }
        String actorSiteId = safe(event.actorSiteId()).trim();
        String peerSiteId = safe(event.peerSiteId()).trim();
        if (currentId.equals(actorSiteId)) {
            return safe(event.peerSiteUrl()).trim();
        }
        if (currentId.equals(peerSiteId)) {
            return safe(event.actorSiteUrl()).trim();
        }
        return "";
    }

    static ParsedHubEvent parseHubMascotBubble(String raw) {
        ParsedHubEvent parsed = parseHubMascotEvent(raw);
        if (parsed.bubble() == null) {
            return new ParsedHubEvent(parsed.eventId(), null, null, null);
        }
        return parsed;
    }

    static ParsedHubEvent parseHubMascotEvent(String raw) {
        JsonNode root = parseJson(raw);
        String eventId = text(root, "id");
        String type = text(root, "type");
        if (HUB_MASCOT_BUBBLE_TYPE.equals(type)) {
            return parseHubMascotBubble(root, eventId);
        }
        if (HUB_MASCOT_ARTICLE_CARD_TYPE.equals(type)) {
            return parseHubMascotArticleCard(root, eventId);
        }
        if (HUB_FRIEND_RELATION_REMOVED_TYPE.equals(type)) {
            return parseFriendRelationRemoved(root, eventId);
        }
        if (HUB_SITE_PROFILE_UPDATED_TYPE.equals(type)) {
            return parseSiteProfileUpdated(root, eventId);
        }
        return new ParsedHubEvent(eventId, null, null, null);
    }

    private static StarGalleryRealtimeEvent parseStarGalleryEvent(String raw) {
        JsonNode root = parseJson(raw);
        String eventId = text(root, "id");
        String type = text(root, "type");
        if (!STAR_GALLERY_EVENT_TYPES.contains(type)) {
            return null;
        }
        JsonNode data = eventData(root);
        return new StarGalleryRealtimeEvent(
            eventId,
            type,
            firstNonBlank(text(data, "siteId"), text(data, "siteID")),
            text(data, "sourceSiteId"),
            text(data, "nodeId"),
            text(data, "contentId")
        );
    }

    private static ParsedHubEvent parseHubMascotBubble(JsonNode root, String eventId) {
        JsonNode data = eventData(root);
        if (data == null) {
            return new ParsedHubEvent(eventId, null, null, null);
        }
        String title = text(data, "title");
        String message = text(data, "message");
        if (title.isBlank() && message.isBlank()) {
            return new ParsedHubEvent(eventId, null, null, null);
        }
        return new ParsedHubEvent(eventId, new HubMascotRealtimeEvent(
            HUB_MASCOT_BUBBLE_TYPE,
            new HubMascotBubbleEvent(
                eventId,
                text(data, "event"),
                text(data, "level"),
                title,
                message,
                text(data, "siteId"),
                text(data, "siteName"),
                text(data, "nodeName"),
                text(data, "nodeAvatar"),
                text(data, "time"),
                text(data, "visibility"),
                readStringList(data.path("targetSiteIds"))
            ),
            null
        ), null, null);
    }

    private static ParsedHubEvent parseHubMascotArticleCard(JsonNode root, String eventId) {
        JsonNode data = eventData(root);
        if (data == null) {
            return new ParsedHubEvent(eventId, null, null, null);
        }
        JsonNode article = data.path("article");
        if (article.isMissingNode() || article.isNull() || text(article, "title").isBlank()
            || text(article, "url").isBlank()) {
            return new ParsedHubEvent(eventId, null, null, null);
        }
        String title = text(data, "title");
        String message = text(data, "message");
        if (title.isBlank() && message.isBlank()) {
            title = text(article, "title");
        }
        return new ParsedHubEvent(eventId, new HubMascotRealtimeEvent(
            HUB_MASCOT_ARTICLE_CARD_TYPE,
            null,
            new HubMascotArticleCardEvent(
                eventId,
                text(data, "event"),
                text(data, "level"),
                title,
                message,
                text(data, "siteId"),
                text(data, "siteName"),
                readString(data, "nodeName", text(article, "nodeName")),
                readString(data, "nodeAvatar", text(article, "nodeAvatar")),
                text(data, "time"),
                text(data, "visibility"),
                readStringList(data.path("targetSiteIds")),
                article,
                text(data, "reason")
            )
        ), null, null);
    }

    /** Parses a friend_relation_removed envelope into the typed event record. */
    private static ParsedHubEvent parseFriendRelationRemoved(JsonNode root, String eventId) {
        JsonNode data = eventData(root);
        if (data == null) {
            return new ParsedHubEvent(eventId, null, null, null);
        }
        String actorSiteId = text(data, "actorSiteId");
        String peerSiteId = text(data, "peerSiteId");
        if (actorSiteId.isBlank() && peerSiteId.isBlank()) {
            return new ParsedHubEvent(eventId, null, null, null);
        }
        return new ParsedHubEvent(eventId, null, new FriendRelationRemovedEvent(
            eventId,
            actorSiteId,
            text(data, "actorSiteUrl"),
            text(data, "actorSiteName"),
            peerSiteId,
            text(data, "peerSiteUrl"),
            text(data, "reason")
        ), null);
    }

    /** Parses a site_profile_updated envelope into the typed event record (Phase 5). */
    private static ParsedHubEvent parseSiteProfileUpdated(JsonNode root, String eventId) {
        JsonNode data = eventData(root);
        if (data == null) {
            return new ParsedHubEvent(eventId, null, null, null);
        }
        String siteId = text(data, "siteId");
        if (siteId.isBlank()) {
            return new ParsedHubEvent(eventId, null, null, null);
        }
        return new ParsedHubEvent(eventId, null, null, new SiteProfileUpdatedEvent(
            eventId,
            siteId,
            text(data, "url"),
            text(data, "name"),
            text(data, "description"),
            text(data, "nodeAvatar"),
            text(data, "rssUrl")
        ));
    }

    private static JsonNode eventData(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return root;
        }
        return data;
    }

    static URI toWebSocketUri(String hubBaseUrl, String token, String lastEventId) {
        String base = normalizeBaseUrl(hubBaseUrl);
        if (base.isBlank()) {
            throw new IllegalArgumentException("hubBaseUrl is invalid");
        }
        String wsBase = base.startsWith("https://")
            ? "wss://" + base.substring("https://".length())
            : "ws://" + base.substring("http://".length());
        StringBuilder query = new StringBuilder("access_token=")
            .append(urlEncode(safe(token)))
            .append("&replayLimit=200");
        if (!safe(lastEventId).isBlank()) {
            query.append("&lastEventId=").append(urlEncode(lastEventId));
        }
        return URI.create(wsBase + HUB_WS_PATH + "?" + query);
    }

    private void updateStatus(boolean connected, String phase, String message) {
        currentStatus.set(new HubRealtimeBridgeStatus(
            connected,
            safe(phase),
            safe(message),
            connected ? safe(lastEventId.get()) : "",
            connected ? "" : nowIso(),
            nowIso()
        ));
    }

    private static Duration sanitizeDelay(Duration delay) {
        if (delay == null || delay.isNegative()) {
            return MIN_RECONNECT_DELAY;
        }
        return delay;
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

    private static JsonNode parseJson(String body) {
        String value = safe(body).trim();
        if (value.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(value);
        } catch (Exception ignored) {
            return MAPPER.createObjectNode();
        }
    }

    private static String normalizeBaseUrl(String raw) {
        String value = safe(raw).trim();
        if (value.isBlank()) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return "";
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

    private static String readString(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = safe(value.asText(""));
        return text.isBlank() ? fallback : text.trim();
    }

    private static String text(JsonNode node, String field) {
        return readString(node, field, "");
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = safe(item.asText("")).trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String safeMessage(String message, String fallback) {
        String value = safe(message).trim();
        return value.isBlank() ? fallback : value;
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private final class HubWebSocketListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            reconnectAttempts.set(0);
            updateStatus(true, "CONNECTED", "hub realtime connected");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    handleHubMessage(message);
                } catch (Exception error) {
                    log.warn("[AstraHub] handle hub realtime event failed", error);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handleCloseOrError("CLOSED", "hub realtime closed: " + statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleCloseOrError("ERROR", error == null ? "" : error.getMessage());
        }
    }

    static final class RecentEventIds {
        private final int limit;
        private final ArrayDeque<String> order = new ArrayDeque<>();
        private final Set<String> ids = new LinkedHashSet<>();

        RecentEventIds(int limit) {
            this.limit = Math.max(1, limit);
        }

        synchronized boolean add(String id) {
            String value = safe(id).trim();
            if (value.isBlank()) {
                return true;
            }
            if (ids.contains(value)) {
                return false;
            }
            ids.add(value);
            order.addLast(value);
            while (order.size() > limit) {
                String removed = order.removeFirst();
                ids.remove(removed);
            }
            return true;
        }
    }

    public record ParsedHubEvent(
        String eventId,
        HubMascotRealtimeEvent event,
        FriendRelationRemovedEvent relationRemoved,
        SiteProfileUpdatedEvent profileUpdated
    ) {
        public HubMascotBubbleEvent bubble() {
            return event == null ? null : event.bubble();
        }

        public HubMascotArticleCardEvent articleCard() {
            return event == null ? null : event.articleCard();
        }
    }

    /** Decoded site_profile_updated event from the hub (Phase 5 reverse sync). */
    public record SiteProfileUpdatedEvent(
        String id,
        String siteId,
        String url,
        String name,
        String description,
        String nodeAvatar,
        String rssUrl
    ) {
    }

    /** Decoded friend_relation_removed event from the hub. */
    public record FriendRelationRemovedEvent(
        String id,
        String actorSiteId,
        String actorSiteUrl,
        String actorSiteName,
        String peerSiteId,
        String peerSiteUrl,
        String reason
    ) {
    }

    public record StarGalleryRealtimeEvent(
        String id,
        String type,
        String siteId,
        String sourceSiteId,
        String nodeId,
        String contentId
    ) {
    }

    public record HubRealtimeBridgeStatus(
        boolean connected,
        String phase,
        String message,
        String lastEventId,
        String lastDisconnectedAt,
        String updatedAt
    ) {
    }

    public record HubMascotBubbleEvent(
        String id,
        String event,
        String level,
        String title,
        String message,
        String siteId,
        String siteName,
        String nodeName,
        String nodeAvatar,
        String time,
        String visibility,
        List<String> targetSiteIds
    ) {
        public HubMascotBubbleEvent {
            targetSiteIds = List.copyOf(Objects.requireNonNullElse(targetSiteIds, List.of()));
        }
    }

    public record HubMascotRealtimeEvent(
        String type,
        HubMascotBubbleEvent bubble,
        HubMascotArticleCardEvent articleCard
    ) {
    }

    public record HubMascotArticleCardEvent(
        String id,
        String event,
        String level,
        String title,
        String message,
        String siteId,
        String siteName,
        String nodeName,
        String nodeAvatar,
        String time,
        String visibility,
        List<String> targetSiteIds,
        JsonNode article,
        String reason
    ) {
        public HubMascotArticleCardEvent {
            targetSiteIds = List.copyOf(Objects.requireNonNullElse(targetSiteIds, List.of()));
            if (article == null || article.isMissingNode() || article.isNull()) {
                article = MAPPER.createObjectNode();
            } else {
                article = article.deepCopy();
            }
        }
    }
}
