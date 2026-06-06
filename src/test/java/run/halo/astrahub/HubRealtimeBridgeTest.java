package run.halo.astrahub;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HubRealtimeBridgeTest {

    @Test
    void shouldBuildSecureHubWebSocketUriWithReplayCursor() {
        URI uri = HubRealtimeBridge.toWebSocketUri(
            "https://hub.example.com/",
            "token value",
            "evt_001"
        );

        assertThat(uri.toString())
            .isEqualTo("wss://hub.example.com/v1/ws?access_token=token+value&replayLimit=200&lastEventId=evt_001");
    }

    @Test
    void shouldBuildPlainHubWebSocketUriWithoutReplayCursor() {
        URI uri = HubRealtimeBridge.toWebSocketUri(
            "http://127.0.0.1:8080",
            "token",
            ""
        );

        assertThat(uri.toString())
            .isEqualTo("ws://127.0.0.1:8080/v1/ws?access_token=token&replayLimit=200");
    }

    @Test
    void shouldParseOnlyMascotBubbleEventsAndKeepEventId() {
        HubRealtimeBridge.ParsedHubEvent parsed = HubRealtimeBridge.parseHubMascotEvent("""
            {
              "id": "evt_abc",
              "type": "mascot_bubble",
              "data": {
                "event": "graph_pushed",
                "level": "success",
                "title": "星链同步完成",
                "message": "Serenity Blog 完成 25 条链接同步",
                "siteId": "site_abc",
                "siteName": "Serenity Blog",
                "nodeName": "热爱可抵岁月漫长ing",
                "nodeAvatar": "https://site.example/avatar.png",
                "time": "2026-04-15T00:00:00Z",
                "visibility": "targeted",
                "targetSiteIds": ["site_abc"]
              }
            }
            """);

        assertThat(parsed.eventId()).isEqualTo("evt_abc");
        assertThat(parsed.bubble()).isNotNull();
        assertThat(parsed.bubble().event()).isEqualTo("graph_pushed");
        assertThat(parsed.bubble().title()).isEqualTo("星链同步完成");
        assertThat(parsed.bubble().targetSiteIds()).containsExactly("site_abc");
    }

    @Test
    void shouldParseMascotArticleCardEvents() {
        HubRealtimeBridge.ParsedHubEvent parsed = HubRealtimeBridge.parseHubMascotEvent("""
            {
              "id": "evt_article",
              "type": "mascot_article_card",
              "data": {
                "title": "主星捕捉到一篇新信号",
                "message": "节点「Nova」有新的内容值得停靠。",
                "siteId": "site_abc",
                "siteName": "Serenity Blog",
                "visibility": "targeted",
                "targetSiteIds": ["site_abc"],
                "reason": "stellar_neighbor",
                "article": {
                  "id": "rss_item_1",
                  "title": "Nova 的新文章",
                  "url": "https://nova.example/post",
                  "nodeName": "Nova",
                  "nodeAvatar": "https://nova.example/avatar.png"
                }
              }
            }
            """);

        assertThat(parsed.eventId()).isEqualTo("evt_article");
        assertThat(parsed.articleCard()).isNotNull();
        assertThat(parsed.articleCard().article().path("url").asText()).isEqualTo("https://nova.example/post");
        assertThat(parsed.articleCard().reason()).isEqualTo("stellar_neighbor");
        assertThat(parsed.articleCard().targetSiteIds()).containsExactly("site_abc");
    }

    @Test
    void shouldKeepNonMascotEventIdWithoutEmittingEvent() {
        HubRealtimeBridge.ParsedHubEvent parsed = HubRealtimeBridge.parseHubMascotEvent("""
            {"id":"evt_graph","type":"graph_pushed","data":{"siteId":"site_abc"}}
            """);

        assertThat(parsed.eventId()).isEqualTo("evt_graph");
        assertThat(parsed.event()).isNull();
    }

    @Test
    void shouldParseFriendRelationRemovedEvent() {
        HubRealtimeBridge.ParsedHubEvent parsed = HubRealtimeBridge.parseHubMascotEvent("""
            {
              "id": "evt_remove",
              "type": "friend_relation_removed",
              "data": {
                "actorSiteId": "site_a",
                "actorSiteUrl": "https://a.example",
                "actorSiteName": "Site A",
                "peerSiteId": "site_b",
                "peerSiteUrl": "https://b.example",
                "reason": "试运行结束"
              }
            }
            """);

        assertThat(parsed.eventId()).isEqualTo("evt_remove");
        // mascot 类事件不应被填充。
        assertThat(parsed.event()).isNull();
        assertThat(parsed.bubble()).isNull();
        // 关键：friend_relation_removed 解析出的双方信息要齐全。
        HubRealtimeBridge.FriendRelationRemovedEvent removed = parsed.relationRemoved();
        assertThat(removed).isNotNull();
        assertThat(removed.actorSiteId()).isEqualTo("site_a");
        assertThat(removed.actorSiteUrl()).isEqualTo("https://a.example");
        assertThat(removed.peerSiteId()).isEqualTo("site_b");
        assertThat(removed.peerSiteUrl()).isEqualTo("https://b.example");
    }

    @Test
    void shouldRejectDuplicateRecentEventIds() {
        HubRealtimeBridge.RecentEventIds recent = new HubRealtimeBridge.RecentEventIds(2);

        assertThat(recent.add("evt_1")).isTrue();
        assertThat(recent.add("evt_1")).isFalse();
        assertThat(recent.add("evt_2")).isTrue();
        assertThat(recent.add("evt_3")).isTrue();
        assertThat(recent.add("evt_1")).isTrue();
    }

    @Test
    void shouldExposePublicBubbleWithoutPrivateRoutingFields() {
        AstraHubPublicStatusService.PublicMascotRealtimeEvent event =
            AstraHubPublicStatusService.toPublicMascotEvent(new HubRealtimeBridge.HubMascotRealtimeEvent(
                HubRealtimeBridge.HUB_MASCOT_BUBBLE_TYPE,
                new HubRealtimeBridge.HubMascotBubbleEvent(
                    "evt_private",
                    "friend_invitation_created",
                    "info",
                    "收到星链邀请",
                    "Nova 向本站发起星链邀请",
                    "site_secret",
                    "Nova Blog",
                    "Nova Node",
                    "https://site.example/avatar.png",
                    "2026-04-15T00:00:00Z",
                    "targeted",
                    List.of("site_secret")
                ),
                null
            ));

        assertThat(event.type()).isEqualTo("mascot_bubble");
        assertThat(event.title()).isEqualTo("收到星链邀请");
        assertThat(event.siteName()).isEqualTo("Nova Blog");
        assertThat(event.nodeName()).isEqualTo("Nova Node");
        assertThat(event.toString()).doesNotContain("site_secret");
    }
}
