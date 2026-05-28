package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AstraHubPlanetClustersService {

    private static final String HUB_PLANET_CLUSTERS_PATH = "/v1/planet/clusters";

    private final AstraHubSignedPlanetReadService signedPlanetReadService;

    public Mono<AstraHubSignedPlanetReadService.SignedReadResult> fetch(String page, String size, String search) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", normalizeNumber(page, "1"));
        query.put("size", normalizeNumber(size, "200"));
        query.put("search", normalize(search));
        return signedPlanetReadService.fetch(HUB_PLANET_CLUSTERS_PATH, query);
    }

    private static String normalizeNumber(String value, String fallback) {
        String raw = normalize(value);
        return raw.isEmpty() ? fallback : raw;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
