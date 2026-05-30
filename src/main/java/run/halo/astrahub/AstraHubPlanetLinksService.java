package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AstraHubPlanetLinksService {

    private static final String HUB_PLANET_LINKS_PATH = "/v1/planet/links";
    private static final String HUB_PLANET_LINKS_RELATIONS_PATH = "/v1/planet/links/relations";

    private final AstraHubSignedPlanetReadService signedPlanetReadService;

    public Mono<PlanetLinksResult> fetch(Map<String, String> query) {
        return signedPlanetReadService.fetch(HUB_PLANET_LINKS_PATH, query == null ? Map.of() : query)
            .map(result -> result.success()
                ? new PlanetLinksResult(true, result.status(), "ok", result.body())
                : PlanetLinksResult.failed(result.status(), result.message()));
    }

    public Mono<PlanetLinksResult> fetchRelations() {
        return signedPlanetReadService.fetch(HUB_PLANET_LINKS_RELATIONS_PATH, Map.of())
            .map(result -> result.success()
                ? new PlanetLinksResult(true, result.status(), "ok", result.body())
                : PlanetLinksResult.failed(result.status(), result.message()));
    }

    public record PlanetLinksResult(
        boolean success,
        int status,
        String message,
        String body
    ) {
        public static PlanetLinksResult failed(int status, String message) {
            return new PlanetLinksResult(false, status, message, "");
        }
    }
}
