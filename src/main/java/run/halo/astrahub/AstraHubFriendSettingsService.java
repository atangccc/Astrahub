package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Service
@RequiredArgsConstructor
public class AstraHubFriendSettingsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;

    public Mono<InvitationSettings> readInvitationSettings() {
        return readSetting("invitation")
            .map(node -> new InvitationSettings(
                readString(node, "defaultInvitationLinkGroup"),
                readBoolean(node, "allowIncomingInvitations", true),
                readBoolean(node, "allowOutgoingInvitations", true)
            ));
    }

    public Mono<ConnectionSettings> readConnectionSettings() {
        return readSetting("connection")
            .map(node -> new ConnectionSettings(
                readString(node, "hubBaseUrl"),
                readString(node, "siteName"),
                readString(node, "siteUrl"),
                readString(node, "siteDescription"),
                readString(node, "contactEmail"),
                readString(node, "siteNodeAvatar"),
                readString(node, "siteRssUrl")
            ));
    }

    private Mono<JsonNode> readSetting(String key) {
        return settingFetcher.getSettingValue(key)
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .onErrorResume(error -> Mono.just(MAPPER.createObjectNode()))
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

    public record InvitationSettings(
        String defaultInvitationLinkGroup,
        boolean allowIncomingInvitations,
        boolean allowOutgoingInvitations
    ) {
    }

    public record ConnectionSettings(
        String hubBaseUrl,
        String siteName,
        String siteUrl,
        String siteDescription,
        String contactEmail,
        String siteNodeAvatar,
        String siteRssUrl
    ) {
    }
}
