package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.Map;

/**
 * 直读插件 ConfigMap 中的 credentials 分组，绕过 {@link run.halo.app.plugin.ReactiveSettingFetcher}
 * 的内存缓存。
 *
 * <p>登舱/注册会让 Hub 立即签发新 apiKey 并作废旧 key，新值随即落库到 ConfigMap。但
 * ReactiveSettingFetcher 的缓存依赖后台 reconciler 异步失效，存在一个窗口期：此时缓存仍是旧
 * apiKey。所有需要用 apiKey 对 Hub 请求签名的调用必须读到最新凭证，否则会以旧 key 签名而被 Hub
 * 以新 key 验签拒绝（invalid signature）。本组件每次直接 fetch ConfigMap，保证读到的是落库真相。</p>
 */
@Component
@RequiredArgsConstructor
public class AstraHubCredentialReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONFIG_MAP_NAME = "plugin-astrahub-configmap";
    private static final String CREDENTIALS_KEY = "credentials";

    private final ReactiveExtensionClient client;

    /**
     * 读取最新的 credentials 分组（含 siteId/apiKey/createdAt）。永不返回空，缺失时返回空对象节点。
     */
    public Mono<JsonNode> readCredentials() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(ConfigMap::getData)
            .flatMap(data -> Mono.fromCallable(() -> parseCredentials(data)))
            .switchIfEmpty(Mono.fromSupplier(MAPPER::createObjectNode));
    }

    private static JsonNode parseCredentials(Map<String, String> data) throws Exception {
        String raw = data == null ? null : data.get(CREDENTIALS_KEY);
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(raw);
    }
}
