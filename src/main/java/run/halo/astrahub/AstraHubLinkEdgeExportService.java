package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AstraHubLinkEdgeExportService {

    private static final String SCHEMA_VERSION = "bp.site-links.v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AstraHubCollectionService collectionService;
    private final AstraHubFriendSettingsService friendSettingsService;
    private final ReactiveSettingFetcher settingFetcher;

    public Mono<LinkEdgesPayload> export() {
        return export(null);
    }

    public Mono<LinkEdgesPayload> export(ServerRequest request) {
        Mono<AstraHubCollectionService.CollectedPayload> collectionMono =
            request == null ? collectionService.collect() : collectionService.collect(request);
        return Mono.zip(collectionMono, friendSettingsService.readConnectionSettings(), readSetting("credentials"))
            .map(tuple -> buildPayload(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    private LinkEdgesPayload buildPayload(
        AstraHubCollectionService.CollectedPayload collected,
        AstraHubFriendSettingsService.ConnectionSettings connection,
        JsonNode credentials
    ) {
        String snapshotAt = normalizeTimestamp(collected.collectedAt());
        List<LinkEdgeItem> edges = collected.links().stream()
            .map(link -> new LinkEdgeItem(
                link.url(),
                link.title(),
                link.description(),
                link.logo(),
                link.rssUrl(),
                true,
                normalizeTimestamp(link.createdAt(), snapshotAt),
                snapshotAt,
                snapshotAt
            ))
            .toList();

        return new LinkEdgesPayload(
            SCHEMA_VERSION,
            snapshotAt,
            new LinkEdgesSource(
                "halo",
                "plugin-astrahub",
                pluginVersion(),
                readString(credentials, "siteId"),
                connection.siteName(),
                connection.siteUrl()
            ),
            edges
        );
    }

    private static String normalizeTimestamp(String raw, String fallback) {
        String normalized = normalizeTimestamp(raw);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalizeTimestamp(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        try {
            return OffsetDateTime.parse(value).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception ignored) {
            return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
    }

    private static String pluginVersion() {
        return Optional.ofNullable(AstraHubLinkEdgeExportService.class.getPackage().getImplementationVersion())
            .orElse("");
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

    private static String readString(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    public record LinkEdgesPayload(
        String version,
        String snapshotAt,
        LinkEdgesSource source,
        List<LinkEdgeItem> edges
    ) {
    }

    public record LinkEdgesSource(
        String platform,
        String plugin,
        String pluginVersion,
        String siteId,
        String siteName,
        String siteUrl
    ) {
    }

    public record LinkEdgeItem(
        String targetUrl,
        String title,
        String description,
        String logo,
        String rssUrl,
        boolean isActive,
        String firstSeenAt,
        String lastSeenAt,
        String updatedAt
    ) {
    }
}
