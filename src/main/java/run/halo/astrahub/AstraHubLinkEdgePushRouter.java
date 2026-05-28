package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class AstraHubLinkEdgePushRouter implements CustomEndpoint {

    private final AstraHubLinkEdgePushService pushService;
    private final AstraHubLinkEdgeExportService exportService;
    private final AstraHubLinkChangeMonitorService linkChangeMonitorService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .POST("astrahub/push-link-edges", this::pushLinkEdges,
                builder -> builder.operationId("PushAstraHubLinkEdges")
                    .tag(tag)
                    .description("Trigger a manual signed site link edge push to AstraHub hub")
                    .response(responseBuilder().description("Push result"))
            )
            .POST("astrahub/repair-link-edges", this::repairLinkEdges,
                builder -> builder.operationId("RepairAstraHubLinkEdges")
                    .tag(tag)
                    .description("Backfill current local Halo links to repair historical AstraHub relation data")
                    .response(responseBuilder().description("Repair result"))
            )
            .GET("astrahub/export-link-edges", this::exportLinkEdges,
                builder -> builder.operationId("ExportAstraHubLinkEdges")
                    .tag(tag)
                    .description("Export local Halo links as AstraHub site link edges payload")
                    .response(responseBuilder().description("Exported payload"))
            )
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> pushLinkEdges(ServerRequest request) {
        return pushService.push(request)
            .flatMap(result -> {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", result.success());
                response.put("status", result.status());
                response.put("message", result.message());
                response.put("responseBody", result.responseBody());
                response.put("pushedAt", result.pushedAt());

                if (result.success()) {
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response);
                }
                int status = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(response);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] push link edges endpoint error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "status", 500,
                        "message", "push failed: " + error.getMessage()
                    ));
            });
    }

    private Mono<ServerResponse> exportLinkEdges(ServerRequest request) {
        return exportService.export(request)
            .flatMap(payload -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "success", true,
                    "payload", payload
                )))
            .onErrorResume(error -> {
                log.error("[AstraHub] export link edges endpoint error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "message", "export failed: " + error.getMessage()
                    ));
            });
    }

    private Mono<ServerResponse> repairLinkEdges(ServerRequest request) {
        return linkChangeMonitorService.observeNow("manual_repair")
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "success", result.success(),
                    "status", result.status(),
                    "trigger", result.trigger(),
                    "message", result.message(),
                    "pushedAt", result.pushedAt(),
                    "skipped", result.skipped()
                )))
            .onErrorResume(error -> {
                log.error("[AstraHub] repair link edges endpoint error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "status", 500,
                        "message", "repair failed: " + error.getMessage()
                    ));
            });
    }
}
