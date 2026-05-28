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
public class AstraHubPushRouter implements CustomEndpoint {

    private final AstraHubReportOrchestratorService orchestratorService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .GET("astrahub/report-status", this::reportStatus,
                builder -> builder.operationId("GetAstraHubReportStatus")
                    .tag(tag)
                    .description("Get current report scheduler status")
                    .response(responseBuilder().description("Report status"))
            )
            .POST("astrahub/push-graph", this::pushGraph,
                builder -> builder.operationId("PushAstraHubGraph")
                    .tag(tag)
                    .description("Trigger a manual signed graph push to AstraHub hub")
                    .response(responseBuilder().description("Push result"))
            )
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> reportStatus(ServerRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("status", orchestratorService.currentStatus());
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(response);
    }

    private Mono<ServerResponse> pushGraph(ServerRequest request) {
        return orchestratorService.triggerManualPush(request)
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
                log.error("[AstraHub] push graph endpoint error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "status", 500,
                        "message", "push failed: " + error.getMessage()
                    ));
            });
    }
}
