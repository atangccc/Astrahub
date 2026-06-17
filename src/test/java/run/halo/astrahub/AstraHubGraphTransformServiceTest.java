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

class AstraHubGraphTransformServiceTest {

    private static final tools.jackson.databind.ObjectMapper TOOLS_MAPPER =
        new tools.jackson.databind.ObjectMapper();

    @Test
    void shouldCarryFriendLinkRssUrlIntoGraphMetaWithoutCollectingArticles() {
        AstraHubCollectionService collectionService = mock(AstraHubCollectionService.class);
        AstraHubContentCollectionService contentCollectionService = mock(AstraHubContentCollectionService.class);
        ReactiveSettingFetcher settingFetcher = mock(ReactiveSettingFetcher.class);
        when(settingFetcher.getSettingValue(anyString())).thenReturn(Mono.empty());
        when(settingFetcher.getSettingValue("connection")).thenReturn(Mono.just(parseSetting("""
            {"siteName":"Serenity","siteUrl":"https://site.example","siteNodeName":"Indie Blog","siteNodeAvatar":"https://site.example/avatar.png"}
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
            List.of(new AstraHubCollectionService.GroupSnapshot("indie", "Indie Blog", 10, List.of("friend-beta"))),
            List.of(new AstraHubCollectionService.LinkSnapshot(
                "friend-beta",
                "Beta Friend",
                "https://friend.example/home",
                "Friend site",
                "https://friend.example/logo.png",
                "https://friend.example/rss.xml",
                8,
                List.of("indie"),
                "2026-03-01T08:00:00Z",
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
        assertThat(payload.contents())
            .extracting(AstraHubGraphTransformService.GraphContent::externalId)
            .containsExactlyInAnyOrder("self-link:site_abc123", "friend-link:friend-beta");

        AstraHubGraphTransformService.GraphContent friendContent = payload.contents().stream()
            .filter(item -> "friend-link".equals(item.meta().get("sourceType")))
            .findFirst()
            .orElseThrow();

        assertThat(friendContent.canonicalUrl()).isEqualTo("https://friend.example/home");
        assertThat(friendContent.meta()).containsEntry("rssUrl", "https://friend.example/rss.xml");
        assertThat(friendContent.tags()).isEmpty();
        assertThat(friendContent.relatedContentExternalIds()).isEmpty();
    }

    private static tools.jackson.databind.JsonNode parseSetting(String value) {
        try {
            return TOOLS_MAPPER.readTree(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
