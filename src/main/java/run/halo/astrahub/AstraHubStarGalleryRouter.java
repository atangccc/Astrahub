package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

@Component
@RequiredArgsConstructor
public class AstraHubStarGalleryRouter implements CustomEndpoint {

    private final AstraHubStarGalleryService starGalleryService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "anonymous.astrahub.halo.run/v1alpha1/AstraHubStarGallery";
        return SpringdocRouteBuilder.route()
            .GET("star-gallery/-/status", request -> status(),
                builder -> builder.operationId("GetAstraHubStarGalleryStatus")
                    .tag(tag)
                    .description("Probe cached public AstraHub star gallery availability")
                    .response(responseBuilder().description("AstraHub star gallery availability")))
            .GET("star-gallery/-/snapshot", request -> snapshot(),
                builder -> builder.operationId("GetAstraHubStarGallerySnapshot")
                    .tag(tag)
                    .description("Get cached public AstraHub star gallery snapshot for theme pages")
                    .response(responseBuilder().description("AstraHub star gallery snapshot")))
            .GET("star-gallery/sectors/{sectorKey}/planets", this::planets,
                builder -> builder.operationId("ListAstraHubStarGalleryPlanets")
                    .tag(tag)
                    .description("List paged star gallery sector planets")
                    .response(responseBuilder().description("AstraHub star gallery sector planets")))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("anonymous.astrahub.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> snapshot() {
        return starGalleryService.current()
            .flatMap(snapshot -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(snapshot));
    }

    private Mono<ServerResponse> status() {
        return starGalleryService.current()
            .map(snapshot -> new StarGalleryStatus(
                snapshot.available(),
                snapshot.generatedAt(),
                snapshot.relationCount()
            ))
            .flatMap(status -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .bodyValue(status));
    }

    private Mono<ServerResponse> planets(ServerRequest request) {
        String sectorKey = request.pathVariable("sectorKey");
        int page = parseInt(request.queryParam("page").orElse("1"), 1);
        int pageSize = parseInt(request.queryParam("pageSize").orElse(request.queryParam("size").orElse("24")), 24);
        return starGalleryService.planets(sectorKey, page, pageSize)
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                .bodyValue(result));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record StarGalleryStatus(
        boolean available,
        String generatedAt,
        int relationCount
    ) {
    }
}
