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
public class AstraHubRegisterRouter implements CustomEndpoint {

    private final AstraHubRegisterService registerService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .POST("astrahub/register", this::registerSite,
                builder -> builder.operationId("RegisterAstraHubSite")
                    .tag(tag)
                    .description("Register current Halo site to AstraHub and return siteId/apiKey")
                    .response(responseBuilder().description("Registration result"))
            )
            .POST("astrahub/invitation/request", this::requestInvitation,
                builder -> builder.operationId("RequestAstraHubInvitation")
                    .tag(tag)
                    .description("Request an invitation code from AstraHub; the code will be emailed to contactEmail")
                    .response(responseBuilder().description("Invitation request result"))
            )
            .POST("astrahub/invitation/register", this::registerWithInvitation,
                builder -> builder.operationId("RegisterAstraHubSiteWithInvitation")
                    .tag(tag)
                    .description("Register current Halo site to AstraHub with an invitation code received via email")
                    .response(responseBuilder().description("Registration result"))
            )
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> registerSite(ServerRequest request) {
        return request.bodyToMono(RegisterRequest.class)
            .switchIfEmpty(Mono.just(new RegisterRequest("", "", "", "", "", "", "", "", "", "")))
            .flatMap(body -> registerService.register(new AstraHubRegisterService.RegisterCommand(
                body.hubBaseUrl(),
                body.registerToken(),
                body.siteName(),
                body.siteUrl(),
                body.siteDescription(),
                body.siteRssUrl(),
                body.siteAvatarUrl(),
                body.contactEmail(),
                body.siteNodeName(),
                body.siteNodeAvatar()
            )))
            .flatMap(result -> {
                Map<String, Object> responseBody = new LinkedHashMap<>();
                responseBody.put("success", result.success());
                responseBody.put("status", result.status());
                responseBody.put("message", result.message());
                responseBody.put("siteId", result.siteId());
                responseBody.put("apiKey", result.apiKey());
                responseBody.put("createdAt", result.createdAt());
                responseBody.put("nodeName", result.nodeName());
                responseBody.put("category", result.category());
                responseBody.put("nodeAvatar", result.nodeAvatar());

                if (result.success()) {
                    log.info("[AstraHub] site registered successfully, siteId={}", result.siteId());
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(responseBody);
                }
                log.warn("[AstraHub] site register failed, status={}, message={}",
                    result.status(), result.message());
                int status = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(responseBody);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] register endpoint error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "status", 500,
                        "message", "internal error: " + error.getMessage()
                    ));
            });
    }

    public record RegisterRequest(
        String hubBaseUrl,
        String registerToken,
        String siteName,
        String siteUrl,
        String siteDescription,
        String siteRssUrl,
        String siteAvatarUrl,
        String contactEmail,
        String siteNodeName,
        String siteNodeAvatar
    ) {
    }

    private Mono<ServerResponse> requestInvitation(ServerRequest request) {
        return request.bodyToMono(InvitationRequestBody.class)
            .switchIfEmpty(Mono.just(new InvitationRequestBody("", "", "")))
            .flatMap(body -> registerService.requestInvitation(
                new AstraHubRegisterService.InvitationRequestCommand(
                    body.hubBaseUrl(),
                    body.contactEmail(),
                    body.siteUrl()
                )
            ))
            .flatMap(result -> {
                Map<String, Object> responseBody = new LinkedHashMap<>();
                responseBody.put("success", result.success());
                responseBody.put("status", result.status());
                responseBody.put("message", result.message());
                responseBody.put("expiresAt", result.expiresAt());
                responseBody.put("cooldownUntil", result.cooldownUntil());

                if (result.success()) {
                    log.info("[AstraHub] invitation code requested, expiresAt={}", result.expiresAt());
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(responseBody);
                }
                log.warn("[AstraHub] request invitation failed, status={}, message={}",
                    result.status(), result.message());
                int status = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(responseBody);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] request invitation endpoint error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "status", 500,
                        "message", "internal error: " + error.getMessage()
                    ));
            });
    }

    private Mono<ServerResponse> registerWithInvitation(ServerRequest request) {
        return request.bodyToMono(InvitationRegisterBody.class)
            .switchIfEmpty(Mono.just(new InvitationRegisterBody(
                "", "", "", "", "", "", "", "", "", ""
            )))
            .flatMap(body -> registerService.registerWithInvitation(
                new AstraHubRegisterService.InvitationRegisterCommand(
                    body.hubBaseUrl(),
                    body.invitationCode(),
                    body.siteName(),
                    body.siteUrl(),
                    body.siteDescription(),
                    body.siteRssUrl(),
                    body.siteAvatarUrl(),
                    body.contactEmail(),
                    body.siteNodeName(),
                    body.siteNodeAvatar()
                )
            ))
            .flatMap(result -> {
                Map<String, Object> responseBody = new LinkedHashMap<>();
                responseBody.put("success", result.success());
                responseBody.put("status", result.status());
                responseBody.put("message", result.message());
                responseBody.put("siteId", result.siteId());
                responseBody.put("apiKey", result.apiKey());
                responseBody.put("createdAt", result.createdAt());
                responseBody.put("nodeName", result.nodeName());
                responseBody.put("category", result.category());
                responseBody.put("nodeAvatar", result.nodeAvatar());

                if (result.success()) {
                    log.info("[AstraHub] site registered with invitation, siteId={}", result.siteId());
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(responseBody);
                }
                log.warn("[AstraHub] register with invitation failed, status={}, message={}",
                    result.status(), result.message());
                int status = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(responseBody);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] register with invitation endpoint error", error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "success", false,
                        "status", 500,
                        "message", "internal error: " + error.getMessage()
                    ));
            });
    }

    private static String body(ServerRequest request) {
        // placeholder to satisfy logger interpolation fallback; real email is in request body and not cached here.
        return request.path();
    }

    public record InvitationRequestBody(
        String hubBaseUrl,
        String contactEmail,
        String siteUrl
    ) {
    }

    public record InvitationRegisterBody(
        String hubBaseUrl,
        String invitationCode,
        String siteName,
        String siteUrl,
        String siteDescription,
        String siteRssUrl,
        String siteAvatarUrl,
        String contactEmail,
        String siteNodeName,
        String siteNodeAvatar
    ) {
    }
}
