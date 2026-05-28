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
public class AstraHubBoardingRouter implements CustomEndpoint {

    private final AstraHubBoardingService boardingService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .POST("astrahub/boarding/send-code", this::sendCode,
                builder -> builder.operationId("SendAstraHubBoardingCode")
                    .tag(tag)
                    .description("Send boarding verification code to contact email")
                    .response(responseBuilder().description("Send code result"))
            )
            .POST("astrahub/boarding/restore", this::restore,
                builder -> builder.operationId("RestoreAstraHubByBoardingCode")
                    .tag(tag)
                    .description("Restore AstraHub site credentials by email code")
                    .response(responseBuilder().description("Restore result"))
            )
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> sendCode(ServerRequest request) {
        return request.bodyToMono(SendCodeRequest.class)
            .switchIfEmpty(Mono.just(new SendCodeRequest("", "")))
            .flatMap(body -> boardingService.sendCode(new AstraHubBoardingService.SendCodeCommand(
                body.hubBaseUrl(),
                body.contactEmail()
            )))
            .flatMap(result -> {
                Map<String, Object> responseBody = new LinkedHashMap<>();
                responseBody.put("success", result.success());
                responseBody.put("status", result.status());
                responseBody.put("message", result.message());
                responseBody.put("expiresAt", result.expiresAt());

                if (result.success()) {
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(responseBody);
                }
                int status = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(responseBody);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] boarding send-code endpoint error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "status", 500,
                        "message", "internal error: " + error.getMessage()
                    ));
            });
    }

    private Mono<ServerResponse> restore(ServerRequest request) {
        return request.bodyToMono(RestoreRequest.class)
            .switchIfEmpty(Mono.just(new RestoreRequest("", "", "")))
            .flatMap(body -> boardingService.restore(new AstraHubBoardingService.RestoreCommand(
                body.hubBaseUrl(),
                body.contactEmail(),
                body.code()
            )))
            .flatMap(result -> {
                Map<String, Object> responseBody = new LinkedHashMap<>();
                responseBody.put("success", result.success());
                responseBody.put("status", result.status());
                responseBody.put("message", result.message());
                responseBody.put("siteId", result.siteId());
                responseBody.put("apiKey", result.apiKey());
                responseBody.put("siteName", result.siteName());
                responseBody.put("siteUrl", result.siteUrl());
                responseBody.put("contactEmail", result.contactEmail());
                responseBody.put("description", result.description());
                responseBody.put("rssUrl", result.rssUrl());
                responseBody.put("nodeName", result.nodeName());
                responseBody.put("category", result.category());
                responseBody.put("nodeAvatar", result.nodeAvatar());
                responseBody.put("createdAt", result.createdAt());

                if (result.success()) {
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(responseBody);
                }
                int status = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(responseBody);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] boarding restore endpoint error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "status", 500,
                        "message", "internal error: " + error.getMessage()
                    ));
            });
    }

    public record SendCodeRequest(
        String hubBaseUrl,
        String contactEmail
    ) {
    }

    public record RestoreRequest(
        String hubBaseUrl,
        String contactEmail,
        String code
    ) {
    }
}
