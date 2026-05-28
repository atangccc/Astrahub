package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AstraHubPublicStatusService {

    public static final String PROTOCOL_VERSION = "v2.4.0";

    private static final String LABEL_SITE = "\u672C\u7AD9";
    private static final String LABEL_UNNAMED_NODE = "\u672A\u547D\u540D\u8282\u70B9";
    private static final String LABEL_NOT_LINKED = "\u672A\u63A5\u5165\u4E3B\u661F";
    private static final String LABEL_PENDING = "\u5F85\u5EFA\u7ACB\u94FE\u8DEF";
    private static final String LABEL_ERROR = "\u94FE\u8DEF\u5F02\u5E38";
    private static final String LABEL_LINKED = "\u6052\u661F\u5DF2\u94FE\u63A5";
    private static final String LINE_NOT_LINKED = "\u5C1A\u672A\u4E0E\u4E3B\u661F\u5EFA\u7ACB\u94FE\u8DEF // \u534F\u8BAE\u7248\u672C ";
    private static final String LINE_PENDING = "\u5DF2\u63A5\u5165\u7AD9\u70B9\u8EAB\u4EFD\uFF0C\u7B49\u5F85\u4E0E\u4E3B\u661F\u5EFA\u7ACB\u94FE\u8DEF // \u534F\u8BAE\u7248\u672C ";
    private static final String LINE_ERROR_PREFIX = "\u4E3B\u661F\u94FE\u8DEF\u5F02\u5E38\uFF1A";
    private static final String LINE_LINKED = "\u5DF2\u4E0E\u4E3B\u661F\u5EFA\u7ACB\u5B9E\u65F6\u94FE\u63A5 // \u534F\u8BAE\u7248\u672C ";
    private static final String FALLBACK_MESSAGE = "\u7B49\u5F85\u4E0B\u4E00\u6B21\u540C\u6B65\u6062\u590D";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;
    private final AstraHubReportOrchestratorService orchestratorService;
    private final HubRealtimeBridge hubRealtimeBridge;

    public Mono<PublicStatusSnapshot> currentStatus() {
        return Mono.zip(
                readSetting("connection"),
                readSetting("credentials"),
                readSetting("widget"),
                readSetting("realtimeBroadcast")
            )
            .map(tuple -> buildSnapshot(
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT3(),
                tuple.getT4(),
                orchestratorService.runtimeStatus()
            ))
            .onErrorResume(error -> Mono.just(PublicStatusSnapshot.fallback()));
    }

    public Flux<PublicStatusSnapshot> statusStream() {
        return orchestratorService.statusStream()
            .flatMap(ignored -> currentStatus())
            .distinctUntilChanged();
    }

    public Flux<PublicMascotRealtimeEvent> mascotStream() {
        return hubRealtimeBridge.mascotStream()
            .flatMap(event -> currentStatus()
                .map(PublicStatusSnapshot::realtimeBroadcast)
                .flatMap(config -> config != null && config.enabled()
                    ? Mono.just(toPublicMascotEvent(event))
                    : Mono.<PublicMascotRealtimeEvent>empty())
            )
            .filter(Objects::nonNull)
            .filter(event -> HubRealtimeBridge.HUB_MASCOT_ARTICLE_CARD_TYPE.equals(event.type())
                || !event.title().isBlank() || !event.message().isBlank());
    }

    private PublicStatusSnapshot buildSnapshot(JsonNode connection,
                                               JsonNode credentials,
                                               JsonNode widget,
                                               JsonNode realtimeBroadcast,
                                               AstraHubReportOrchestratorService.RuntimeStatus runtimeStatus) {
        String siteName = readString(connection, "siteName", LABEL_SITE);
        String siteUrl = readString(connection, "siteUrl", "");
        String hubBaseUrl = normalizeUrl(readString(connection, "hubBaseUrl", ""));
        String nodeName = readString(connection, "siteNodeName", LABEL_UNNAMED_NODE);
        String nodeAvatar = readString(connection, "siteNodeAvatar", "");

        String siteId = readString(credentials, "siteId", "");
        String apiKey = readString(credentials, "apiKey", "");
        boolean widgetEnabled = readBoolean(widget, "enabled", true);
        PublicRealtimeBroadcastSettings realtimeBroadcastSettings = buildRealtimeBroadcastSettings(realtimeBroadcast);
        boolean registered = !siteId.isBlank() && !apiKey.isBlank();
        boolean runtimeFailure = isRuntimeFailure(runtimeStatus.phase(), runtimeStatus.status());
        boolean connected = registered && runtimeStatus.connected();
        boolean linked = registered && connected;
        boolean healthy = linked && !runtimeFailure;

        String primarySuffix;
        String secondaryLine;
        String statusLabel;
        if (!registered) {
            primarySuffix = " " + LABEL_NOT_LINKED;
            secondaryLine = LINE_NOT_LINKED + PROTOCOL_VERSION;
            statusLabel = LABEL_NOT_LINKED;
        } else if (!connected) {
            primarySuffix = " " + LABEL_PENDING;
            secondaryLine = LINE_PENDING + PROTOCOL_VERSION;
            statusLabel = LABEL_PENDING;
        } else if (runtimeFailure) {
            primarySuffix = " " + LABEL_ERROR;
            secondaryLine = LINE_ERROR_PREFIX + summarizeMessage(runtimeStatus.message())
                + " // \u534F\u8BAE\u7248\u672C " + PROTOCOL_VERSION;
            statusLabel = LABEL_ERROR;
        } else {
            primarySuffix = " " + LABEL_LINKED;
            secondaryLine = LINE_LINKED + PROTOCOL_VERSION;
            statusLabel = LABEL_LINKED;
        }

        String displayNodeName = nodeName.isBlank() ? LABEL_UNNAMED_NODE : nodeName;
        return new PublicStatusSnapshot(
            true,
            widgetEnabled,
            displayNodeName,
            siteName,
            siteUrl,
            hubBaseUrl,
            displayNodeName,
            nodeAvatar,
            registered,
            linked,
            healthy,
            statusLabel,
            primarySuffix,
            secondaryLine,
            safe(runtimeStatus.phase()),
            runtimeStatus.status(),
            safe(runtimeStatus.message()),
            safe(runtimeStatus.lastSuccessfulPushAt()),
            safe(runtimeStatus.nextRunAt()),
            safe(runtimeStatus.updatedAt()),
            realtimeBroadcastSettings
        );
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

    private static boolean isRuntimeFailure(String phase, int status) {
        return "ERROR".equalsIgnoreCase(safe(phase)) || status >= 400;
    }

    private static String summarizeMessage(String message) {
        String value = safe(message).trim();
        if (value.isBlank()) {
            return FALLBACK_MESSAGE;
        }
        if (value.length() <= 32) {
            return value;
        }
        return value.substring(0, 32) + "...";
    }

    private static String readString(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText("");
        if (text == null) {
            return fallback;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static boolean readBoolean(JsonNode node, String field, boolean fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return value.asBoolean(fallback);
    }

    private static int readInt(JsonNode node, String field, int fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return value.asInt(fallback);
    }

    private static PublicRealtimeBroadcastSettings buildRealtimeBroadcastSettings(JsonNode node) {
        return new PublicRealtimeBroadcastSettings(
            readBoolean(node, "enabled", true),
            Math.max(5, readInt(node, "minIntervalSeconds", 15))
        );
    }

    private static String normalizeUrl(String url) {
        String value = safe(url).trim();
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static PublicMascotRealtimeEvent toPublicMascotEvent(HubRealtimeBridge.HubMascotRealtimeEvent event) {
        if (event == null) {
            return null;
        }
        if (HubRealtimeBridge.HUB_MASCOT_ARTICLE_CARD_TYPE.equals(event.type())) {
            HubRealtimeBridge.HubMascotArticleCardEvent card = event.articleCard();
            if (card == null) {
                return null;
            }
            return new PublicMascotRealtimeEvent(
                HubRealtimeBridge.HUB_MASCOT_ARTICLE_CARD_TYPE,
                safe(card.id()),
                safe(card.event()),
                safe(card.level()),
                safe(card.title()),
                safe(card.message()),
                safe(card.siteName()),
                safe(card.nodeName()),
                safe(card.nodeAvatar()),
                safe(card.time()),
                safe(card.visibility()),
                card.article(),
                safe(card.reason())
            );
        }
        HubRealtimeBridge.HubMascotBubbleEvent bubble = event.bubble();
        if (bubble == null) {
            return null;
        }
        return new PublicMascotRealtimeEvent(
            "mascot_bubble",
            safe(bubble.id()),
            safe(bubble.event()),
            safe(bubble.level()),
            safe(bubble.title()),
            safe(bubble.message()),
            safe(bubble.siteName()),
            safe(bubble.nodeName()),
            safe(bubble.nodeAvatar()),
            safe(bubble.time()),
            safe(bubble.visibility()),
            null,
            ""
        );
    }

    public record PublicStatusSnapshot(
        boolean available,
        boolean widgetEnabled,
        String brand,
        String siteName,
        String siteUrl,
        String hubBaseUrl,
        String nodeName,
        String nodeAvatar,
        boolean registered,
        boolean linked,
        boolean healthy,
        String statusLabel,
        String primarySuffix,
        String secondaryLine,
        String phase,
        int status,
        String message,
        String pushedAt,
        String nextRunAt,
        String updatedAt,
        PublicRealtimeBroadcastSettings realtimeBroadcast
    ) {
        public static PublicStatusSnapshot fallback() {
            return new PublicStatusSnapshot(
                true,
                true,
                LABEL_UNNAMED_NODE,
                LABEL_SITE,
                "",
                "",
                LABEL_UNNAMED_NODE,
                "",
                false,
                false,
                false,
                LABEL_NOT_LINKED,
                " " + LABEL_NOT_LINKED,
                LINE_NOT_LINKED + PROTOCOL_VERSION,
                "UNKNOWN",
                0,
                "",
                "",
                "",
                "",
                new PublicRealtimeBroadcastSettings(
                    true,
                    15
                )
            );
        }
    }

    public record PublicRealtimeBroadcastSettings(
        boolean enabled,
        int minIntervalSeconds
    ) {
    }

    public record PublicMascotRealtimeEvent(
        String type,
        String id,
        String event,
        String level,
        String title,
        String message,
        String siteName,
        String nodeName,
        String nodeAvatar,
        String time,
        String visibility,
        JsonNode article,
        String reason
    ) {
    }
}
