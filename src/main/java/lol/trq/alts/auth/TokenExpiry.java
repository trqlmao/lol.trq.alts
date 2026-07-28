package lol.trq.alts.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import lol.trq.alts.model.AltAccount;

/**
 * Expiry arithmetic for Minecraft access tokens. An account's stored expiry is authoritative when
 * known; otherwise the token's own {@code exp} claim is read, and when neither is available the token
 * is treated as expired, because renewing an already-valid session is cheap and installing a dead one
 * is not.
 *
 * @author trq
 * @since 0.6.0
 */
public final class TokenExpiry {

    /**
     * How far ahead of the real expiry a token is considered spent, so a session that would lapse
     * during the handshake is renewed first.
     *
     * @since 0.6.0
     */
    public static final long SKEW_MILLIS = 60_000L;

    private TokenExpiry() {}

    /**
     * Reads the {@code exp} claim from a JWT access token.
     *
     * @param token the access token, which may be null, empty, opaque, or malformed
     * @return the expiry in epoch millis, or {@code 0} when the token carries no readable claim
     * @since 0.6.0
     */
    public static long jwtExpiryMillis(String token) {
        if (token == null || !token.startsWith("eyJ")) {
            return 0L;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return 0L;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padded(parts[1]));
            JsonObject payload = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return payload.has("exp") ? payload.get("exp").getAsLong() * 1000L : 0L;
        } catch (Exception unreadable) {
            return 0L;
        }
    }

    /**
     * Returns whether an account's access token is spent, within the skew margin.
     *
     * @param account the account to inspect
     * @param clock the clock to read the current time from
     * @return true if the token should be renewed before use
     * @since 0.6.0
     */
    public static boolean isExpired(AltAccount account, Clock clock) {
        long expiry = account.expiresAt() > 0 ? account.expiresAt() : jwtExpiryMillis(account.accessToken());
        if (expiry <= 0) {
            return true;
        }
        return clock.millis() >= expiry - SKEW_MILLIS;
    }

    private static String padded(String base64Url) {
        int padding = (4 - base64Url.length() % 4) % 4;
        return base64Url + "=".repeat(padding);
    }
}
