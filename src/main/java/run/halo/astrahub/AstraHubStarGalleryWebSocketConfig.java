package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import run.halo.app.core.endpoint.WebSocketEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@RequiredArgsConstructor
public class AstraHubStarGalleryWebSocketConfig implements WebSocketEndpoint {

    private final AstraHubStarGalleryWebSocketHandler starGalleryWebSocketHandler;

    @Override
    public String urlPath() {
        return "/astrahub/ws/star-gallery";
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("anonymous.astrahub.halo.run/v1alpha1");
    }

    @Override
    public WebSocketHandler handler() {
        return starGalleryWebSocketHandler;
    }
}
