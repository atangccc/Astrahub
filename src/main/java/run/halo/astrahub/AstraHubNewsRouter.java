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

/**
 * Plugin-side proxy for the AstraHub planet rss-deep-space endpoints.
 * Uses signed planet read because hub's frontend endpoints are protected
 * by same-origin + cookie guard which third-party plugins cannot satisfy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AstraHubNewsRouter implements CustomEndpoint {

    private static final String HUB_BROWSE_PATH = "/v1/planet/rss-deep-space/browse";
    private static final String HUB_DISCOVER_PATH = "/v1/planet/rss-deep-space/discover";
    private static final String HUB_SEARCH_PATH = "/v1/planet/rss-deep-space/search";

    private final AstraHubSignedPlanetReadService signedPlanetReadService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .GET("astrahub/news/browse",
                request -> proxy(HUB_BROWSE_PATH, mapBrowseQuery(request)),
                builder -> builder.operationId("ListAstraHubNewsBrowse").tag(tag)
                    .description("Proxy GET to /v1/planet/rss-deep-space/browse (signed)")
                    .response(responseBuilder().description("News browse")))
            .GET("astrahub/news/discover",
                request -> proxy(HUB_DISCOVER_PATH, mapDiscoverQuery(request)),
                builder -> builder.operationId("ListAstraHubNewsDiscover").tag(tag)
                    .description("Proxy GET to /v1/planet/rss-deep-space/discover (signed)")
                    .response(responseBuilder().description("News sources")))
            .GET("astrahub/news/search",
                request -> proxy(HUB_SEARCH_PATH, mapSearchQuery(request)),
                builder -> builder.operationId("ListAstraHubNewsSearch").tag(tag)
                    .description("Proxy GET to /v1/planet/rss-deep-space/search (signed)")
                    .response(responseBuilder().description("News search")))
            .build();
    }

    private Mono<ServerResponse> proxy(String hubPath, Map<String, String> query) {
        return signedPlanetReadService.fetch(hubPath, query)
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
                log.error("[AstraHub] news proxy failed path={}", hubPath, error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"message\":\"internal error\"}");
            });
    }

    private static Map<String, String> mapBrowseQuery(ServerRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        putIfPresent(result, "page", request.queryParam("page").orElse(""));
        // hub uses `size`, plugin frontend may pass `pageSize` for symmetry
        String size = firstNonBlank(
            request.queryParam("size").orElse(""),
            request.queryParam("pageSize").orElse("")
        );
        putIfPresent(result, "size", size);
        putIfPresent(result, "cursor", request.queryParam("cursor").orElse(""));
        putIfPresent(result, "onlyMyGalaxy", request.queryParam("onlyMyGalaxy").orElse(""));
        return result;
    }

    private static Map<String, String> mapDiscoverQuery(ServerRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        // hub discover uses `size` not `limit`
        String size = firstNonBlank(
            request.queryParam("size").orElse(""),
            request.queryParam("limit").orElse("")
        );
        putIfPresent(result, "size", size);
        putIfPresent(result, "cursor", request.queryParam("cursor").orElse(""));
        return result;
    }

    private static Map<String, String> mapSearchQuery(ServerRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        putIfPresent(result, "q", request.queryParam("q").orElse(""));
        putIfPresent(result, "page", request.queryParam("page").orElse(""));
        String size = firstNonBlank(
            request.queryParam("size").orElse(""),
            request.queryParam("pageSize").orElse("")
        );
        putIfPresent(result, "size", size);
        putIfPresent(result, "cursor", request.queryParam("cursor").orElse(""));
        putIfPresent(result, "onlyMyGalaxy", request.queryParam("onlyMyGalaxy").orElse(""));
        return result;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
