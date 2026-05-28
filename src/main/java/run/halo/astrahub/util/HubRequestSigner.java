package run.halo.astrahub.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * AstraHub Hub 请求签名工具。
 *
 * <p>签名协议 v1（与 Hub 服务端约定一致）：</p>
 * <pre>
 *   canonical = METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + SHA256(BODY)
 *   signature = HMAC-SHA256(apiKey, canonical)
 * </pre>
 *
 * <p>请求需要携带以下 Header（由调用方设置）：</p>
 * <ul>
 *   <li>X-BP-Site-Id</li>
 *   <li>X-BP-Timestamp</li>
 *   <li>X-BP-Nonce</li>
 *   <li>X-BP-Signature</li>
 * </ul>
 *
 * <p>本类为不可实例化的工具类。</p>
 */
public final class HubRequestSigner {

    private HubRequestSigner() {
    }

    /**
     * 对一次请求生成签名所需字段。
     *
     * @param method  HTTP 方法，如 GET / POST（不区分大小写）
     * @param path    请求路径，例如 /v1/friend-invitations
     * @param body    请求体原始字符串；GET 等无请求体时传入空串 ""
     * @param siteId  站点 ID
     * @param apiKey  站点 API Key（用作 HMAC 密钥）
     * @return 签名结果
     * @throws Exception HMAC / SHA-256 初始化异常
     */
    public static SignedRequest signRequest(String method,
                                            String path,
                                            String body,
                                            String siteId,
                                            String apiKey) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodyHash = sha256Hex(body == null ? "" : body);
        String canonical = method.toUpperCase(Locale.ROOT)
            + "\n" + path
            + "\n" + timestamp
            + "\n" + nonce
            + "\n" + bodyHash;
        String signature = hmacSha256Hex(apiKey, canonical);
        return new SignedRequest(siteId, timestamp, nonce, signature);
    }

    /** HMAC-SHA256 十六进制字符串。 */
    public static String hmacSha256Hex(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    /** SHA-256 十六进制字符串。 */
    public static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8)));
    }

    /** 字节数组转小写十六进制字符串。 */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 签名结果：包含请求需要的全部鉴权字段。
     *
     * @param siteId    站点 ID（来自配置）
     * @param timestamp Unix 秒级时间戳
     * @param nonce     随机串（UUID 去掉横线）
     * @param signature HMAC-SHA256 十六进制签名
     */
    public record SignedRequest(String siteId,
                                String timestamp,
                                String nonce,
                                String signature) {
    }
}
