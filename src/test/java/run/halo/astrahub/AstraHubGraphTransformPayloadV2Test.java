package run.halo.astrahub;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AstraHubGraphTransformPayloadV2Test {

    private static final tools.jackson.databind.ObjectMapper TOOLS_MAPPER =
        new tools.jackson.databind.ObjectMapper();

    @Test
    void shouldExposeFriendGroupsOnlyAndNotArticleGroups() {
        AstraHubCollectionService collectionService = mock(AstraHubCollectionService.class);
        AstraHubContentCollectionService contentCollectionService = mock(AstraHubContentCollectionService.class);

        ReactiveSettingFetcher settingFetcher = mock(ReactiveSettingFetcher.class);
        when(settingFetcher.getSettingValue(anyString())).thenReturn(Mono.empty());
        when(settingFetcher.getSettingValue("connection")).thenReturn(Mono.just(parseSetting("""
            {"siteName":"Star Notes","siteUrl":"https://site.example","siteNodeName":"Indie Blog","siteNodeAvatar":"https://site.example/avatar.png"}
            """)));
        when(settingFetcher.getSettingValue("credentials")).thenReturn(Mono.just(parseSetting("""
            {"siteId":"site_abc123"}
            """)));
        when(settingFetcher.getSettingValues()).thenReturn(Mono.just(Map.of()));

        when(collectionService.collect()).thenReturn(Mono.just(new AstraHubCollectionService.CollectedPayload(
            "2026-04-03T12:00:00Z",
            1,
            1,
            1,
            0,
            List.of(new AstraHubCollectionService.GroupSnapshot("tech", "Tech Friends", 5, List.of("friend-alpha"))),
            List.of(new AstraHubCollectionService.LinkSnapshot(
                "friend-alpha",
                "Alpha Friend",
                "https://alpha.example",
                "Alpha site",
                "",
                "",
                1,
                List.of("tech"),
                "2026-04-01T08:00:00Z",
                ""
            ))
        )));
        when(contentCollectionService.resolveSiteBaseInfo()).thenReturn(Mono.just(
            new AstraHubContentCollectionService.SiteBaseInfo("2026-04-03T12:05:00Z", "https://site.example")
        ));

        AstraHubGraphTransformService service =
            new AstraHubGraphTransformService(collectionService, contentCollectionService, settingFetcher);

        AstraHubGraphTransformService.GraphPayload payload = service.buildBpGraphV1Payload().block();

        assertThat(payload).isNotNull();
        assertThat(payload.groups())
            .extracting(AstraHubGraphTransformService.GraphGroup::externalId)
            .containsExactly("friend-group:tech");

        AstraHubGraphTransformService.GraphContent content = payload.contents().stream()
            .filter(item -> "friend-link:friend-alpha".equals(item.externalId()))
            .findFirst()
            .orElseThrow();
        assertThat(content.sourceCategory()).isEmpty();
        assertThat(content.series()).isEmpty();
        assertThat(content.groupExternalIds()).containsExactly("friend-group:tech");
        assertThat(content.topics()).containsExactly("Tech Friends");
        assertThat(content.meta()).containsEntry("sourceType", "friend-link");
        assertThat(content.meta()).doesNotContainKeys("sourceCategory", "series");
    }

    private static tools.jackson.databind.JsonNode parseSetting(String json) {
        try {
            return TOOLS_MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse test setting json", ex);
        }
    }
}
