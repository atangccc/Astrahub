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
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    // Keep avatar probing off Reactor Netty. Some friend sites reset TLS handshakes
    // for bots; Reactor logs those resets at WARN before business error handling.
    private static final HttpClient AVATAR_HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
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

        // SSRF 防护：在发起请求前解析目标主机的所有 IP，命中私网/环回/链路本地
        // (含云元数据 169.254.169.254)/多播/未指定地址即拒绝。与 Go 端头像代理
        // (frontend_avatar.go 的拨号层 isDisallowedDialIP) 保持一致的硬化基线。
        // 注意：WebClient 默认会自动跟随重定向，若不在此关闭，攻击者可用一个公网
        // URL 302 跳转到内网绕过本校验，因此下方 AVATAR_CLIENT 已禁用重定向跟随。
        final String host = uri.getHost();
        return Mono.fromCallable(() -> resolveAndValidateHost(host))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(allowed -> {
                if (!allowed) {
                    return ServerResponse.badRequest().bodyValue("blocked host");
                }
                return fetchAvatar(uri, rawUrl);
            })
            .onErrorResume(error -> {
                // 头像代理校验失败属于"友链方域名解析不到 / 已下线 / 私网地址"等正常情况，
                // 数量大且非异常路径，避免在 WARN 级别污染日志，下沉到 DEBUG 即可。
                log.debug("[AstraHub] avatar proxy host validation failed url={}", rawUrl, error);
                return ServerResponse.badRequest().bodyValue("blocked host");
            });
    }

    private Mono<ServerResponse> fetchAvatar(URI uri, String rawUrl) {
        return Mono.fromCallable(() -> fetchAvatarBlocking(uri))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(result -> {
                if (result.status() < 200 || result.status() >= 300) {
                    return ServerResponse.status(result.status()).build();
                }
                String contentType = result.contentType().isBlank() ? "image/png" : result.contentType();
                String lowerContentType = contentType.toLowerCase();
                if (!lowerContentType.startsWith("image/") && !lowerContentType.contains("svg")) {
                    return ServerResponse.badRequest().bodyValue("not an image");
                }
                byte[] bytes = result.bytes();
                if (bytes.length == 0 || bytes.length > AVATAR_MAX_BYTES) {
                    return ServerResponse.status(413).bodyValue("payload too large or empty");
                }
                DataBuffer buf = new DefaultDataBufferFactory().wrap(bytes);
                return ServerResponse.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                    .body((resp, ctx) -> resp.writeWith(Mono.just(buf)));
            })
            .onErrorResume(error -> {
                // Upstream avatar 4xx/5xx/timeout/connection resets are normal for
                // friend links and should not pollute production WARN logs.
                log.debug("[AstraHub] avatar proxy failed url={}", rawUrl, error);
                return ServerResponse.status(502).bodyValue("upstream failed");
            });
    }

    private static AvatarFetchResult fetchAvatarBlocking(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(8))
            .header(HttpHeaders.USER_AGENT, "AstraHub-Plugin-Avatar/1.0")
            .header(HttpHeaders.ACCEPT, "image/*,*/*;q=0.8")
            .GET()
            .build();

        HttpResponse<InputStream> response =
            AVATAR_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("");

        try (InputStream body = response.body()) {
            if (status < 200 || status >= 300) {
                return new AvatarFetchResult(status, contentType, new byte[0]);
            }
            return new AvatarFetchResult(status, contentType, readLimited(body, AVATAR_MAX_BYTES + 1));
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = maxBytes - total;
            if (remaining <= 0) {
                break;
            }
            int writable = Math.min(read, remaining);
            output.write(buffer, 0, writable);
            total += read;
            if (total > maxBytes) {
                break;
            }
        }
        return output.toByteArray();
    }

    /**
     * 解析主机名的所有 A/AAAA 记录，逐个校验目标 IP 是否允许访问。
     * 任意一个解析结果落在内网/环回/链路本地/多播/未指定地址段即整体拒绝，
     * 以此根除基于 DNS 的 SSRF（攻击者把自有域名解析到内网地址）。
     *
     * @return 全部解析结果均为公网地址时返回 true；否则 false。
     */
    private static boolean resolveAndValidateHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        // 去掉 IPv6 字面量两端的方括号，便于 getAllByName 解析。
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalized);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (isDisallowedAddress(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    /**
     * 判定一个已解析出的 IP 是否禁止访问（内网/环回/链路本地/多播/未指定/站点本地）。
     * 等价于 Go 端 isDisallowedDialIP 的判定口径。
     */
    private static boolean isDisallowedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        return address.isAnyLocalAddress()        // 0.0.0.0 / ::
            || address.isLoopbackAddress()        // 127.0.0.0/8 / ::1
            || address.isLinkLocalAddress()       // 169.254.0.0/16（含云元数据）/ fe80::/10
            || address.isSiteLocalAddress()       // 10/172.16/192.168 等私网 / fec0::
            || address.isMulticastAddress()       // 224.0.0.0/4 / ff00::/8
            || isUniqueLocalIPv6(address)         // fc00::/7（IPv6 唯一本地，Java 未单独覆盖）
            || isCgnatOrExtraPrivate(address);    // 100.64.0.0/10 运营商级 NAT
    }

    /** IPv6 唯一本地地址 fc00::/7（isSiteLocalAddress 对 IPv6 不覆盖此段）。 */
    private static boolean isUniqueLocalIPv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes != null && bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** IPv4 运营商级 NAT 100.64.0.0/10（Java 标准方法未覆盖）。 */
    private static boolean isCgnatOrExtraPrivate(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes == null || bytes.length != 4) {
            return false;
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        return b0 == 100 && b1 >= 64 && b1 <= 127;
    }

    private static String escapeJson(String value) {
        return String.valueOf(value == null ? "" : value)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    private record AvatarFetchResult(int status, String contentType, byte[] bytes) {
    }
}
