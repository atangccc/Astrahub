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

class AstraHubGraphTransformUrlNormalizationTest {

    private static final tools.jackson.databind.ObjectMapper TOOLS_MAPPER =
        new tools.jackson.databind.ObjectMapper();

    @Test
    void shouldNormalizeNodeAvatarAndCoverToAbsoluteUrls() {
        AstraHubCollectionService collectionService = mock(AstraHubCollectionService.class);
        AstraHubContentCollectionService contentCollectionService = mock(AstraHubContentCollectionService.class);

        ReactiveSettingFetcher settingFetcher = mock(ReactiveSettingFetcher.class);
        when(settingFetcher.getSettingValue(anyString())).thenReturn(Mono.empty());
        when(settingFetcher.getSettingValue("connection")).thenReturn(Mono.just(parseSetting("""
            {"siteName":"Star Notes","siteUrl":"https://site.example","siteNodeName":"Indie Blog","siteNodeAvatar":"/avatar.png"}
            """)));
        when(settingFetcher.getSettingValue("credentials")).thenReturn(Mono.just(parseSetting("""
            {"siteId":"site_abc123"}
            """)));
        when(settingFetcher.getSettingValues()).thenReturn(Mono.just(Map.of()));

        when(collectionService.collect()).thenReturn(Mono.just(new AstraHubCollectionService.CollectedPayload(
            "2026-04-03T12:00:00Z",
            0,
            0,
            0,
            0,
            List.of(),
            List.of()
        )));

        when(contentCollectionService.collect()).thenReturn(Mono.just(new AstraHubContentCollectionService.CollectedContentPayload(
            "2026-04-03T12:05:00Z",
            "https://site.example",
            List.of(
                new AstraHubContentCollectionService.CollectedContent(
                    "post-1",
                    "post",
                    "https://site.example/posts/graph-engine",
                    "Graph Engine Design",
                    "Relationship graph overview",
                    "/covers/graph.png",
                    "Serenity",
                    List.of("Graph"),
                    List.of("Creator Graph"),
                    List.of(),
                    "2026-04-01T08:00:00Z",
                    "2026-04-03T10:00:00Z",
                    "2026-04-01T08:00:00Z",
                    "published",
                    "public",
                    "zh-CN",
                    1200,
                    "no links",
                    "<p>plain</p>",
                    Map.of("contentKind", "post")
                )
            )
        )));

        AstraHubGraphTransformService service =
            new AstraHubGraphTransformService(collectionService, contentCollectionService, settingFetcher);

        AstraHubGraphTransformService.GraphPayload payload = service.buildBpGraphV1Payload().block();

        assertThat(payload).isNotNull();
        assertThat(AstraHubGraphTransformService.normalizeContentUrl("/avatar.png", "https://site.example/"))
            .isEqualTo("https://site.example/avatar.png");
        assertThat(payload.source().siteUrl()).isEqualTo("https://site.example/");
        assertThat(payload.source().nodeAvatar()).isEqualTo("https://site.example/avatar.png");
        assertThat(payload.contents())
            .extracting(AstraHubGraphTransformService.GraphContent::externalId)
            .contains("self-link:site_abc123", "post-1");
        AstraHubGraphTransformService.GraphContent content = payload.contents().stream()
            .filter(item -> "post-1".equals(item.externalId()))
            .findFirst()
            .orElseThrow();
        assertThat(content.cover()).isEqualTo("https://site.example/covers/graph.png");
    }

    private static tools.jackson.databind.JsonNode parseSetting(String json) {
        try {
            return TOOLS_MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse test setting json", ex);
        }
    }
}
