package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class AstraHubPlanetFeedRouter implements CustomEndpoint {

    private final AstraHubPlanetFeedService planetFeedService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .GET("astrahub/planet-feed", this::planetFeed,
                builder -> builder.operationId("ListAstraHubPlanetFeed")
                    .tag(tag)
                    .description("Load private planet feed from AstraHub Hub through plugin proxy")
                    .response(responseBuilder().description("Planet feed")))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private reactor.core.publisher.Mono<ServerResponse> planetFeed(ServerRequest request) {
        return planetFeedService.fetch(
                request.queryParam("page").orElse(""),
                request.queryParam("size").orElse(""),
                request.queryParam("tag").orElse("")
            )
            .flatMap(result -> {
                if (result.success()) {
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result.body());
                }
                int status = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"message\":\"" + escapeJson(result.message()) + "\"}");
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] load planet feed error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"message\":\"internal error\"}");
            });
    }

    private static String escapeJson(String value) {
        return String.valueOf(value == null ? "" : value)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }
}
