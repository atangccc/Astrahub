package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 站点基础信息解析器。
 *
 * <p>历史上该服务还负责抓取站点 /posts 文章并随图谱一起上报，但主星（Blog Planet）已能
 * 通过上报的站点 RSS 链接自行解析文章，插件端不再抓取、不再上报任何文章内容。因此这里
 * 只保留"解析站点 baseUrl"这一项职责：baseUrl 仍是友链 URL 规范化与 self-link 生成的必要
 * 输入。彻底移除文章采集后，逐篇抓取文章详情可能导致的性能、超时与隐私问题随之消失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubContentCollectionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReactiveSettingFetcher settingFetcher;

    public Mono<SiteBaseInfo> resolveSiteBaseInfo() {
        return resolveSiteBaseInfo(null);
    }

    public Mono<SiteBaseInfo> resolveSiteBaseInfo(ServerRequest request) {
        return resolveBaseUrl(request)
            .map(baseUrl -> new SiteBaseInfo(nowIso(), baseUrl == null ? "" : baseUrl))
            .onErrorResume(error -> {
                log.warn("[AstraHub] failed to resolve site base url for graph push", error);
                return Mono.just(new SiteBaseInfo(nowIso(), ""));
            });
    }

    private Mono<String> resolveBaseUrl(ServerRequest request) {
        return settingFetcher.getSettingValue("connection")
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .map(connection -> normalizeBaseUrl(readString(connection, "siteUrl", "")))
            .onErrorResume(error -> Mono.just(""))
            .map(configured -> configured.isBlank() ? resolveRequestBaseUrl(request) : configured)
            .map(baseUrl -> baseUrl == null ? "" : baseUrl);
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

    private static String resolveRequestBaseUrl(ServerRequest request) {
        if (request == null) {
            return "";
        }

        String proto = firstHeaderValue(request, "X-Forwarded-Proto");
        if (proto.isEmpty()) {
            proto = parseForwardedField(request.headers().firstHeader("Forwarded"), "proto");
        }
        if (proto.isEmpty()) {
            proto = trim(request.uri().getScheme());
        }

        String host = firstHeaderValue(request, "X-Forwarded-Host");
        if (host.isEmpty()) {
            host = parseForwardedField(request.headers().firstHeader("Forwarded"), "host");
        }
        if (host.isEmpty()) {
            host = firstHeaderValue(request, "Host");
        }
        if (host.isEmpty()) {
            host = trim(request.uri().getAuthority());
        }

        if (proto.isEmpty() || host.isEmpty()) {
            return "";
        }
        return normalizeBaseUrl(proto + "://" + host);
    }

    private static String firstHeaderValue(ServerRequest request, String header) {
        String raw = trim(request.headers().firstHeader(header));
        if (raw.isEmpty()) {
            return "";
        }
        int comma = raw.indexOf(',');
        if (comma >= 0) {
            return trim(raw.substring(0, comma));
        }
        return raw;
    }

    private static String parseForwardedField(String header, String key) {
        String raw = trim(header);
        if (raw.isEmpty()) {
            return "";
        }
        String first = raw;
        int comma = first.indexOf(',');
        if (comma >= 0) {
            first = first.substring(0, comma);
        }
        for (String token : first.split(";")) {
            String value = trim(token);
            if (!value.regionMatches(true, 0, key + "=", 0, key.length() + 1)) {
                continue;
            }
            String extracted = trim(value.substring(key.length() + 1));
            if (extracted.startsWith("\"") && extracted.endsWith("\"") && extracted.length() >= 2) {
                extracted = extracted.substring(1, extracted.length() - 1);
            }
            return trim(extracted);
        }
        return "";
    }

    private static String normalizeBaseUrl(String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
            return uri.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readString(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = trim(value.asText(""));
        return text.isEmpty() ? fallback : text;
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 站点基础信息：仅承载采集时间与站点 baseUrl，不再包含任何文章内容。
     */
    public record SiteBaseInfo(String collectedAt, String baseUrl) {
    }
}
