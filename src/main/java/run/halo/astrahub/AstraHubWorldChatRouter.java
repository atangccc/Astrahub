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
public class AstraHubWorldChatRouter implements CustomEndpoint {

    private final AstraHubWorldChatService worldChatService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .GET("astrahub/world-chat/consent", this::consent,
                builder -> builder.operationId("GetAstraHubWorldChatConsent").tag(tag)
                    .description("Load world chat consent policy through signed Hub proxy")
                    .response(responseBuilder().description("World chat consent policy")))
            .POST("astrahub/world-chat/consent", this::saveConsent,
                builder -> builder.operationId("SaveAstraHubWorldChatConsent").tag(tag)
                    .description("Save world chat consent through signed Hub proxy")
                    .response(responseBuilder().description("World chat consent state")))
            .GET("astrahub/world-chat/bootstrap", this::bootstrap,
                builder -> builder.operationId("AstraHubWorldChatBootstrap").tag(tag)
                    .description("Load world chat bootstrap through signed Hub proxy")
                    .response(responseBuilder().description("World chat bootstrap")))
            .GET("astrahub/world-chat/messages", this::messages,
                builder -> builder.operationId("ListAstraHubWorldChatMessages").tag(tag)
                    .description("List world chat messages through signed Hub proxy")
                    .response(responseBuilder().description("World chat messages")))
            .GET("astrahub/world-chat/unread", this::unread,
                builder -> builder.operationId("GetAstraHubWorldChatUnread").tag(tag)
                    .description("Get world chat unread count through signed Hub proxy")
                    .response(responseBuilder().description("World chat unread count")))
            .POST("astrahub/world-chat/read", this::markRead,
                builder -> builder.operationId("MarkAstraHubWorldChatRead").tag(tag)
                    .description("Mark world chat as read through signed Hub proxy")
                    .response(responseBuilder().description("World chat read cursor")))
            .POST("astrahub/world-chat/messages", this::sendMessage,
                builder -> builder.operationId("SendAstraHubWorldChatMessage").tag(tag)
                    .description("Send world chat message through signed Hub proxy")
                    .response(responseBuilder().description("World chat send result")))
            .POST("astrahub/world-chat/messages/{messageId}/retract", this::retractMessage,
                builder -> builder.operationId("RetractAstraHubWorldChatMessage").tag(tag)
                    .description("Retract own world chat message through signed Hub proxy")
                    .response(responseBuilder().description("World chat retract result")))
            .GET("astrahub/world-chat/members", this::members,
                builder -> builder.operationId("ListAstraHubWorldChatMembers").tag(tag)
                    .description("List world chat members through signed Hub proxy")
                    .response(responseBuilder().description("World chat members")))
            .GET("astrahub/world-chat/mention-candidates", this::mentionCandidates,
                builder -> builder.operationId("ListAstraHubWorldChatMentionCandidates").tag(tag)
                    .description("List world chat mention candidates through signed Hub proxy")
                    .response(responseBuilder().description("World chat mention candidates")))
            .GET("astrahub/world-chat/members/{siteId}", this::memberDetail,
                builder -> builder.operationId("GetAstraHubWorldChatMember").tag(tag)
                    .description("Load world chat member detail through signed Hub proxy")
                    .response(responseBuilder().description("World chat member detail")))
            .GET("astrahub/world-chat/members/{siteId}/recent-posts", this::memberRecentPosts,
                builder -> builder.operationId("ListAstraHubWorldChatMemberRecentPosts").tag(tag)
                    .description("Load world chat member recent posts through signed Hub proxy")
                    .response(responseBuilder().description("World chat member recent posts")))
            .GET("astrahub/world-chat/stickers", this::stickers,
                builder -> builder.operationId("ListAstraHubWorldChatStickers").tag(tag)
                    .description("List world chat stickers through signed Hub proxy")
                    .response(responseBuilder().description("World chat stickers")))
            .POST("astrahub/world-chat/stickers", this::uploadSticker,
                builder -> builder.operationId("UploadAstraHubWorldChatSticker").tag(tag)
                    .description("Upload world chat sticker through signed Hub proxy")
                    .response(responseBuilder().description("World chat sticker upload result")))
            .GET("astrahub/world-chat/stickers/{stickerId}/file", this::stickerFile,
                builder -> builder.operationId("GetAstraHubWorldChatStickerFile").tag(tag)
                    .description("Load world chat sticker file through signed Hub proxy")
                    .response(responseBuilder().description("World chat sticker file")))
            .POST("astrahub/world-chat/stickers/{stickerId}/delete", this::deleteSticker,
                builder -> builder.operationId("DeleteAstraHubWorldChatSticker").tag(tag)
                    .description("Delete world chat sticker through signed Hub proxy")
                    .response(responseBuilder().description("World chat sticker delete result")))
            .POST("astrahub/world-chat/messages/{messageId}/reports", this::reportMessage,
                builder -> builder.operationId("ReportAstraHubWorldChatMessage").tag(tag)
                    .description("Report world chat message through signed Hub proxy")
                    .response(responseBuilder().description("World chat report result")))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> consent(ServerRequest request) {
        return respond(worldChatService.get("/v1/world-chat/consent", Map.of()));
    }

    private Mono<ServerResponse> saveConsent(ServerRequest request) {
        return request.bodyToMono(String.class)
            .defaultIfEmpty("{}")
            .flatMap(body -> respond(worldChatService.post("/v1/world-chat/consent", body)));
    }

    private Mono<ServerResponse> bootstrap(ServerRequest request) {
        return respond(worldChatService.get("/v1/world-chat/bootstrap", Map.of()));
    }

    private Mono<ServerResponse> messages(ServerRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        request.queryParam("beforeMessageId").ifPresent(value -> query.put("beforeMessageId", value));
        request.queryParam("afterMessageId").ifPresent(value -> query.put("afterMessageId", value));
        request.queryParam("limit").ifPresent(value -> query.put("limit", value));
        return respond(worldChatService.get("/v1/world-chat/messages", query));
    }

    private Mono<ServerResponse> unread(ServerRequest request) {
        return respond(worldChatService.get("/v1/world-chat/unread", Map.of()));
    }

    private Mono<ServerResponse> markRead(ServerRequest request) {
        return request.bodyToMono(String.class)
            .defaultIfEmpty("{}")
            .flatMap(body -> respond(worldChatService.post("/v1/world-chat/read", body)));
    }

    private Mono<ServerResponse> sendMessage(ServerRequest request) {
        return request.bodyToMono(String.class)
            .defaultIfEmpty("{}")
            .flatMap(body -> respond(worldChatService.post("/v1/world-chat/messages", body)));
    }

    private Mono<ServerResponse> retractMessage(ServerRequest request) {
        String messageId = request.pathVariable("messageId");
        return respond(worldChatService.post("/v1/world-chat/messages/" + messageId + "/retract", "{}"));
    }

    private Mono<ServerResponse> members(ServerRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        request.queryParam("offset").ifPresent(value -> query.put("offset", value));
        request.queryParam("limit").ifPresent(value -> query.put("limit", value));
        return respond(worldChatService.get("/v1/world-chat/members", query));
    }

    private Mono<ServerResponse> mentionCandidates(ServerRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        request.queryParam("query").ifPresent(value -> query.put("query", value));
        request.queryParam("offset").ifPresent(value -> query.put("offset", value));
        request.queryParam("limit").ifPresent(value -> query.put("limit", value));
        return respond(worldChatService.get("/v1/world-chat/mention-candidates", query));
    }

    private Mono<ServerResponse> memberDetail(ServerRequest request) {
        return respond(worldChatService.get("/v1/world-chat/members/" + request.pathVariable("siteId"), Map.of()));
    }

    private Mono<ServerResponse> memberRecentPosts(ServerRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        request.queryParam("limit").ifPresent(value -> query.put("limit", value));
        return respond(worldChatService.get("/v1/world-chat/members/" + request.pathVariable("siteId") + "/recent-posts", query));
    }

    private Mono<ServerResponse> stickers(ServerRequest request) {
        return respond(worldChatService.get("/v1/world-chat/stickers", Map.of()));
    }

    private Mono<ServerResponse> uploadSticker(ServerRequest request) {
        return request.bodyToMono(String.class)
            .defaultIfEmpty("{}")
            .flatMap(body -> respond(worldChatService.post("/v1/world-chat/stickers", body)));
    }

    private Mono<ServerResponse> stickerFile(ServerRequest request) {
        String stickerId = request.pathVariable("stickerId");
        return worldChatService.getBytes("/v1/world-chat/stickers/" + stickerId + "/file", Map.of())
            .flatMap(proxy -> {
                int status = proxy.status() >= 400 ? proxy.status() : 200;
                MediaType contentType;
                try {
                    contentType = MediaType.parseMediaType(proxy.contentType());
                } catch (Exception ignored) {
                    contentType = MediaType.APPLICATION_OCTET_STREAM;
                }
                return ServerResponse.status(status)
                    .contentType(contentType)
                    .header("X-Content-Type-Options", "nosniff")
                    .bodyValue(proxy.body());
            });
    }

    private Mono<ServerResponse> deleteSticker(ServerRequest request) {
        String stickerId = request.pathVariable("stickerId");
        return respond(worldChatService.post("/v1/world-chat/stickers/" + stickerId + "/delete", "{}"));
    }

    private Mono<ServerResponse> reportMessage(ServerRequest request) {
        String messageId = request.pathVariable("messageId");
        return request.bodyToMono(String.class)
            .defaultIfEmpty("{}")
            .flatMap(body -> respond(worldChatService.post("/v1/world-chat/messages/" + messageId + "/reports", body)));
    }

    private Mono<ServerResponse> respond(Mono<AstraHubWorldChatService.ProxyResult> result) {
        return result.flatMap(proxy -> {
                if (proxy.success()) {
                    return ServerResponse.status(proxy.status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(proxy.body());
                }
                int status = proxy.status() >= 400 ? proxy.status() : 400;
                String body = proxy.body().isBlank()
                    ? "{\"message\":\"" + escapeJson(proxy.message()) + "\"}"
                    : proxy.body();
                return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body);
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] world chat proxy endpoint error", error);
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
