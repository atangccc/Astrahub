package run.halo.astrahub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class AstraHubFriendInvitationProtocol {

    public static final String PROTOCOL_VERSION = "v1";

    public static final String HEADER_SITE_ID = "X-BP-Site-Id";
    public static final String HEADER_TIMESTAMP = "X-BP-Timestamp";
    public static final String HEADER_NONCE = "X-BP-Nonce";
    public static final String HEADER_SIGNATURE = "X-BP-Signature";
    public static final String HEADER_IDEMPOTENCY_KEY = "X-BP-Idempotency-Key";
    public static final String HEADER_PROTOCOL_VERSION = "X-BP-Protocol-Version";

    public static final String ERROR_ALREADY_PENDING = "INVITATION_ALREADY_PENDING";
    public static final String ERROR_NOT_FOUND = "INVITATION_NOT_FOUND";
    public static final String ERROR_NOT_REVIEWED = "INVITATION_NOT_REVIEWED";
    public static final String ERROR_ALREADY_REVIEWED = "INVITATION_ALREADY_REVIEWED";

    private AstraHubFriendInvitationProtocol() {
    }

    public static String buildIdempotencyKey(String fromSiteId, String toSiteId, String message) {
        try {
            String payload = normalize(fromSiteId) + "\n" + normalize(toSiteId) + "\n" + normalize(message);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return "fi_" + HexFormat.of().formatHex(hash, 0, 16);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to build friend invitation idempotency key", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
