package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

/**
 * Plugin-side proxy for AstraHub graph endpoints used by the relation graph
 * panel. Hub graph APIs require HMAC-signed requests, so the plugin frontend
 * cannot call them directly and must go through this signed proxy.
 *
 * <p>Endpoints exposed:</p>
 * <ul>
 *   <li>GET astrahub/graph/my-site &rarr; /v1/graph/sites/{ownSiteId}
 *       (returns own site summary so the front-end can resolve its nodeId)</li>
 *   <li>GET astrahub/graph/nodes &rarr; /v1/graph/nodes
 *       (full node list — used to seed isolated nodes onto the relation graph)</li>
 *   <li>GET astrahub/graph/nodes/{nodeId} &rarr; /v1/graph/nodes/{nodeId}
 *       (real friend-link expansion — first-degree friends list)</li>
 *   <li>GET astrahub/graph/sites/{siteId}/relations
 *       &rarr; /v1/graph/sites/{siteId}/relations
 *       (algorithm-recommended relations — for the "show recommendations"
 *       overlay in the relation graph panel)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AstraHubGraphRouter implements CustomEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final WebClient AVATAR_CLIENT = WebClient.builder()
        .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
        .build();
    /** Max payload returned to the browser. */
    private static final int AVATAR_MAX_BYTES = 2 * 1024 * 1024;

    private final AstraHubSignedPlanetReadService signedPlanetReadService;
    private final ReactiveSettingFetcher settingFetcher;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AstraHub";
        return SpringdocRouteBuilder.route()
            .GET("astrahub/graph/my-site", this::mySite,
                builder -> builder.operationId("GetAstraHubGraphMySite").tag(tag)
                    .description("Proxy GET to /v1/graph/sites/{ownSiteId} (signed)")
                    .response(responseBuilder().description("Own site graph summary")))
            .GET("astrahub/graph/nodes", this::nodesList,
                builder -> builder.operationId("ListAstraHubGraphNodes").tag(tag)
                    .description("Proxy GET to /v1/graph/nodes (signed) — full node list, used by relation graph to seed isolated nodes")
                    .response(responseBuilder().description("Graph node list")))
            .GET("astrahub/graph/nodes/{nodeId}", this::nodeDetail,
                builder -> builder.operationId("GetAstraHubGraphNodeDetail").tag(tag)
                    .description("Proxy GET to /v1/graph/nodes/{nodeId} (signed)")
                    .response(responseBuilder().description("Graph node detail with friendLinks")))
            .GET("astrahub/graph/sites/{siteId}/relations", this::siteRelations,
                builder -> builder.operationId("ListAstraHubGraphSiteRelations").tag(tag)
                    .description("Proxy GET to /v1/graph/sites/{siteId}/relations (signed)")
                    .response(responseBuilder().description("Algorithm-recommended site relations")))
            .GET("astrahub/graph/avatar", this::avatar,
                builder -> builder.operationId("ProxyAstraHubGraphAvatar").tag(tag)
                    .description("Same-origin proxy for remote avatar images, used by the relation graph canvas")
                    .response(responseBuilder().description("Image bytes")))
            .build();
    }

    private Mono<ServerResponse> mySite(ServerRequest request) {
        return readOwnSiteId()
            .flatMap(siteId -> {
                if (siteId.isEmpty()) {
                    return ServerResponse.status(400)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"message\":\"siteId is required (please connect to AstraHub first)\"}");
                }
                String safe = sanitizePathSegment(siteId);
                if (safe == null) {
                    return ServerResponse.status(400)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"message\":\"siteId is invalid\"}");
                }
                return proxy("/v1/graph/sites/" + safe, mapDetailQuery(request));
            });
    }

    private Mono<ServerResponse> nodeDetail(ServerRequest request) {
        String safe = sanitizePathSegment(request.pathVariable("nodeId"));
        if (safe == null) {
            return ServerResponse.status(400)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"nodeId is invalid\"}");
        }
        return proxy("/v1/graph/nodes/" + safe, mapDetailQuery(request));
    }

    private Mono<ServerResponse> nodesList(ServerRequest request) {
        return proxy("/v1/graph/nodes", mapNodesListQuery(request));
    }

    private Mono<ServerResponse> siteRelations(ServerRequest request) {
        String safe = sanitizePathSegment(request.pathVariable("siteId"));
        if (safe == null) {
            return ServerResponse.status(400)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"siteId is invalid\"}");
        }
        return proxy("/v1/graph/sites/" + safe + "/relations", mapRelationsQuery(request));
    }

    private Mono<ServerResponse> proxy(String hubPath, Map<String, String> query) {
        return signedPlanetReadService.fetch(hubPath, query)
            .flatMap(result -> {
                if (result.success()) {
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result.body());
                }
                int status = result.status() >= 400 ? result.status() : 400;
                return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"message\":\"" + escapeJson(result.message()) + "\"}");
            })
            .onErrorResume(error -> {
                log.error("[AstraHub] graph proxy failed path={}", hubPath, error);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"message\":\"internal error\"}");
            });
    }

    /**
     * Used by both /sites/{id} and /nodes/{id}. Hub default page size is small;
     * for the relation graph we want a generous size so a single fetch covers
     * the whole friendLinks list of a node.
     */
    private static Map<String, String> mapDetailQuery(ServerRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        putIfPresent(result, "page", request.queryParam("page").orElse(""));
        String size = firstNonBlank(
            request.queryParam("size").orElse(""),
            request.queryParam("pageSize").orElse("")
        );
        putIfPresent(result, "size", size);
        putIfPresent(result, "relationType", request.queryParam("relationType").orElse(""));
        putIfPresent(result, "sort", request.queryParam("sort").orElse(""));
        return result;
    }

    private static Map<String, String> mapRelationsQuery(ServerRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        putIfPresent(result, "page", request.queryParam("page").orElse(""));
        String size = firstNonBlank(
            request.queryParam("size").orElse(""),
            request.queryParam("pageSize").orElse("")
        );
        putIfPresent(result, "size", size);
        putIfPresent(result, "relationType", request.queryParam("relationType").orElse(""));
        putIfPresent(result, "sort", request.queryParam("sort").orElse(""));
        return result;
    }

    /**
     * Pass-through query for the /v1/graph/nodes list endpoint. Hub accepts
     * page / size / sort / updatedSince; the relation graph frontend only uses
     * page+size+sort (sort=recommendation) but we keep updatedSince available
     * for future filtering.
     */
    private static Map<String, String> mapNodesListQuery(ServerRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        putIfPresent(result, "page", request.queryParam("page").orElse(""));
        String size = firstNonBlank(
            request.queryParam("size").orElse(""),
            request.queryParam("pageSize").orElse("")
        );
        putIfPresent(result, "size", size);
        putIfPresent(result, "sort", request.queryParam("sort").orElse(""));
        putIfPresent(result, "updatedSince", request.queryParam("updatedSince").orElse(""));
        return result;
    }

    private Mono<String> readOwnSiteId() {
        return settingFetcher.getSettingValue("credentials")
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .map(node -> {
                String siteId = node.path("siteId").asText("");
                return siteId == null ? "" : siteId.trim();
            })
            .onErrorReturn("")
            .defaultIfEmpty("");
    }

    private static JsonNode toJsonNode(Object value) throws Exception {
        if (value == null) {
            return MAPPER.createObjectNode();
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        String raw = value.toString();
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(raw);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * Validate a hub-side path segment (siteId / nodeId).
     *
     * <p>Hub node IDs can be Chinese / contain spaces / unicode (it's the
     * site node display name). We only reject characters that would change
     * routing semantics: forward slash (path traversal across segments),
     * question mark (query starts here), hash (fragment), or null bytes.</p>
     *
     * <p>Returns the trimmed raw value (NOT percent-encoded) so the signing
     * service can build a canonical signature that matches the hub side
     * (hub validates against {@code r.URL.Path} which is already decoded).</p>
     *
     * @return the raw path segment, or {@code null} if invalid.
     */
    private static String sanitizePathSegment(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 256) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#' || ch == '\u0000') {
                return null;
            }
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    /**
     * Same-origin avatar proxy: GET astrahub/graph/avatar?url={remoteUrl}
     *
     * <p>Many friend-link blogs do not serve CORS headers, so loading their
     * avatar directly into a Three.js texture (which goes through {@code img.src}
     * with {@code crossOrigin="anonymous"}) silently fails. This proxy fetches
     * the bytes server-side and returns them under the same origin as the
     * Halo console, so {@code crossOrigin} is no longer needed.</p>
     */
    private Mono<ServerResponse> avatar(ServerRequest request) {
        String rawUrl = request.queryParam("url").orElse("").trim();
        if (rawUrl.isEmpty()) {
            return ServerResponse.badRequest().bodyValue("missing url");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException ex) {
            return ServerResponse.badRequest().bodyValue("invalid url");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return ServerResponse.badRequest().bodyValue("only http/https supported");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return ServerResponse.badRequest().bodyValue("invalid host");
        }

        return AVATAR_CLIENT.get()
            .uri(uri)
            .header(HttpHeaders.USER_AGENT, "AstraHub-Plugin-Avatar/1.0")
            .header(HttpHeaders.ACCEPT, "image/*,*/*;q=0.8")
            .exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (status < 200 || status >= 300) {
                    return response.releaseBody().then(ServerResponse.status(status).build());
                }
                String contentType = response.headers().contentType()
                    .map(MediaType::toString)
                    .orElse("image/png");
                if (!contentType.toLowerCase().startsWith("image/") && !contentType.toLowerCase().contains("svg")) {
                    return response.releaseBody().then(ServerResponse.badRequest().bodyValue("not an image"));
                }
                return response.bodyToMono(byte[].class)
                    .flatMap(bytes -> {
                        if (bytes.length == 0 || bytes.length > AVATAR_MAX_BYTES) {
                            return ServerResponse.status(413).bodyValue("payload too large or empty");
                        }
                        DataBuffer buf = new DefaultDataBufferFactory().wrap(bytes);
                        return ServerResponse.ok()
                            .contentType(MediaType.parseMediaType(contentType))
                            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                            .body((resp, ctx) -> resp.writeWith(Mono.just(buf)));
                    });
            })
            .timeout(Duration.ofSeconds(8))
            .onErrorResume(error -> {
                log.warn("[AstraHub] avatar proxy failed url={}", rawUrl, error);
                return ServerResponse.status(502).bodyValue("upstream failed");
            });
    }

    private static String escapeJson(String value) {
        return String.valueOf(value == null ? "" : value)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }
}
