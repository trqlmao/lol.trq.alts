package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import org.junit.jupiter.api.Test;

class TokenExpiryTest {

    private static final long NOW = 1_800_000_000_000L;

    private static Clock at(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static String jwtExpiringAt(long epochSeconds) {
        String payload = "{\"name\":\"Alex\",\"id\":\"abc\",\"exp\":" + epochSeconds + "}";
        String encoded =
                Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "eyJhbGciOiJIUzI1NiJ9." + encoded + ".sig";
    }

    private static AltAccount accountWith(String accessToken, long expiresAt) {
        return AltAccount.of("u", "Alex", accessToken, AccountType.MICROSOFT).withTokens(accessToken, "r", expiresAt);
    }

    @Test
    void readsTheExpiryClaimFromAJwt() {
        assertEquals(1_800_000_500_000L, TokenExpiry.jwtExpiryMillis(jwtExpiringAt(1_800_000_500L)));
    }

    @Test
    void reportsNoExpiryForNonJwtTokens() {
        assertEquals(0L, TokenExpiry.jwtExpiryMillis("not-a-jwt"));
        assertEquals(0L, TokenExpiry.jwtExpiryMillis(""));
        assertEquals(0L, TokenExpiry.jwtExpiryMillis(null));
    }

    @Test
    void storedExpiryInThePastIsExpired() {
        assertTrue(TokenExpiry.isExpired(accountWith("opaque", NOW - 1), at(NOW)));
    }

    @Test
    void storedExpiryComfortablyAheadIsLive() {
        assertFalse(TokenExpiry.isExpired(accountWith("opaque", NOW + 600_000L), at(NOW)));
    }

    @Test
    void expiryInsideTheSkewMarginCountsAsExpired() {
        assertTrue(
                TokenExpiry.isExpired(accountWith("opaque", NOW + TokenExpiry.SKEW_MILLIS - 1), at(NOW)),
                "a token expiring mid-handshake must be renewed first");
    }

    @Test
    void unknownStoredExpiryFallsBackToTheJwtClaim() {
        AltAccount live = accountWith(jwtExpiringAt((NOW + 600_000L) / 1000L), 0L);
        AltAccount dead = accountWith(jwtExpiringAt((NOW - 600_000L) / 1000L), 0L);

        assertFalse(TokenExpiry.isExpired(live, at(NOW)));
        assertTrue(TokenExpiry.isExpired(dead, at(NOW)));
    }

    @Test
    void whollyUnknownExpiryIsTreatedAsExpired() {
        assertTrue(
                TokenExpiry.isExpired(accountWith("opaque", 0L), at(NOW)),
                "with no expiry signal at all, renewing is the safe default");
    }
}
