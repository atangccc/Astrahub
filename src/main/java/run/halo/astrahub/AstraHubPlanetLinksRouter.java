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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class AstraHubPlanetLinksRouter implements CustomEndpoint {

    private final AstraHubPlanetLinksService planetLinksService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .GET("astrahub/planet-links", request -> planetLinksService.fetch(buildQuery(request))
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
                        log.error("[AstraHub] load planet links error", error);
                        return ServerResponse.status(500)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue("{\"message\":\"internal error\"}");
                    }),
                builder -> builder.operationId("ListAstraHubPlanetLinks")
                    .tag(tag)
                    .description("Load public planet links from AstraHub Hub through plugin proxy")
                    .response(responseBuilder().description("Planet links")))
            .build();
    }

    private static Map<String, String> buildQuery(ServerRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        request.queryParam("size").ifPresent(value -> putIfNotBlank(query, "size", value));
        request.queryParam("cursor").ifPresent(value -> putIfNotBlank(query, "cursor", value));
        request.queryParam("tag").ifPresent(value -> putIfNotBlank(query, "tag", value));
        request.queryParam("keyword").ifPresent(value -> putIfNotBlank(query, "keyword", value));
        request.queryParam("relation").ifPresent(value -> putIfNotBlank(query, "relation", value));
        return query;
    }

    private static void putIfNotBlank(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private static String escapeJson(String value) {
        return String.valueOf(value == null ? "" : value)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }
}
