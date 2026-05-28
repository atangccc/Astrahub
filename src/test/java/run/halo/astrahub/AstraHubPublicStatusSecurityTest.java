package run.halo.astrahub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AstraHubPublicStatusSecurityTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final tools.jackson.databind.ObjectMapper TOOLS_MAPPER =
        new tools.jackson.databind.ObjectMapper();

    @Test
    void shouldNotExposeCredentialOrTokenFieldsInPublicStatus() throws Exception {
        ReactiveSettingFetcher settingFetcher = mock(ReactiveSettingFetcher.class);
        when(settingFetcher.getSettingValue(anyString())).thenReturn(Mono.empty());
        when(settingFetcher.getSettingValue("connection")).thenReturn(Mono.just(parseSetting("""
            {
              "hubBaseUrl":"https://hub.example",
              "siteName":"Serenity Blog",
              "siteUrl":"https://site.example",
              "siteNodeName":"Warm Node",
              "siteNodeAvatar":"https://site.example/avatar.png"
            }
            """)));
        when(settingFetcher.getSettingValue("credentials")).thenReturn(Mono.just(parseSetting("""
            {"siteId":"site_1234567890abcdef","apiKey":"api_secret_value"}
            """)));

        AstraHubReportOrchestratorService orchestratorService = mock(AstraHubReportOrchestratorService.class);
        when(orchestratorService.runtimeStatus()).thenReturn(new AstraHubReportOrchestratorService.RuntimeStatus(
            true,
            "2026-04-15T02:00:00Z",
            "IDLE",
            200,
            "ok",
            "",
            "2026-04-15T02:00:00Z"
        ));

        AstraHubPublicStatusService service = new AstraHubPublicStatusService(
            settingFetcher,
            orchestratorService,
            mock(HubRealtimeBridge.class)
        );

        String json = JSON_MAPPER.writeValueAsString(service.currentStatus().block());

        assertThat(json)
            .doesNotContain("apiKey")
            .doesNotContain("api_secret_value")
            .doesNotContain("site_1234567890abcdef")
            .doesNotContain("siteId")
            .doesNotContain("siteIdMasked")
            .doesNotContain("token")
            .doesNotContain("access_token");
    }

    private static tools.jackson.databind.JsonNode parseSetting(String json) {
        try {
            return TOOLS_MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse test setting json", ex);
        }
    }
}
