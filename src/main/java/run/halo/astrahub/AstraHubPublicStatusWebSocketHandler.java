package run.halo.astrahub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class AstraHubPublicStatusWebSocketHandler implements WebSocketHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AstraHubPublicStatusService publicStatusService;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Flux<String> statusMessages = Flux.concat(
                publicStatusService.currentStatus().flux(),
                publicStatusService.statusStream()
            )
            .map(this::toJson);
        Flux<String> mascotMessages = publicStatusService.mascotStream()
            .map(this::toJson);

        return session.send(Flux.merge(statusMessages, mascotMessages)
                .map(session::textMessage))
            .and(session.receive().then());
    }

    private String toJson(AstraHubPublicStatusService.PublicStatusSnapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (Exception error) {
            log.warn("[AstraHub] serialize public status event failed", error);
            return "{\"available\":true,\"linked\":false,\"healthy\":false,\"statusLabel\":\"status unavailable\"}";
        }
    }

    private String toJson(AstraHubPublicStatusService.PublicMascotRealtimeEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception error) {
            log.warn("[AstraHub] serialize public mascot realtime event failed", error);
            return "{\"type\":\"mascot_bubble\",\"level\":\"info\",\"title\":\"\",\"message\":\"\"}";
        }
    }
}
