package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
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
public class AstraHubPublicStatusRouter implements CustomEndpoint {

    private final AstraHubPublicStatusService publicStatusService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "anonymous.astrahub.halo.run/v1alpha1/AstraHubStatus";
        return SpringdocRouteBuilder.route()
            .GET("status/-/public", this::publicStatus,
                builder -> builder.operationId("GetAstraHubPublicStatus")
                    .tag(tag)
                    .description("Get public AstraHub status snapshot for theme widgets")
                    .response(responseBuilder().description("AstraHub public status")))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("anonymous.astrahub.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> publicStatus(ServerRequest request) {
        return publicStatusService.currentStatus()
            .flatMap(snapshot -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(snapshot));
    }
}
