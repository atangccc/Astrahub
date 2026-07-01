package run.halo.astrahub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class AstraHubStarGalleryWebSocketHandler implements WebSocketHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AstraHubStarGalleryService starGalleryService;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        return session.send(starGalleryService.stream()
                .map(this::toJson)
                .map(session::textMessage))
            .and(session.receive().then());
    }

    private String toJson(AstraHubStarGalleryService.StarGallerySnapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (Exception error) {
            log.warn("[AstraHub] serialize star gallery snapshot failed", error);
            return "{\"available\":false,\"profile\":null,\"sectors\":[],\"posts\":[],\"relationCount\":0}";
        }
    }
}
