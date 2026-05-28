package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AstraHubPlanetFeedService {

    private static final String HUB_PLANET_FEED_PATH = "/v1/planet/feed";

    private final AstraHubSignedPlanetReadService signedPlanetReadService;

    public Mono<AstraHubSignedPlanetReadService.SignedReadResult> fetch(String page, String size, String tag) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", normalizeNumber(page, "1"));
        query.put("size", normalizeNumber(size, "20"));
        query.put("tag", normalize(tag));
        return signedPlanetReadService.fetch(HUB_PLANET_FEED_PATH, query);
    }

    private static String normalizeNumber(String value, String fallback) {
        String raw = normalize(value);
        return raw.isEmpty() ? fallback : raw;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
