package lol.trq.alts.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * What a pasted line is taken to be. Getting this wrong is worse than refusing: a session token sent to
 * the refresh route comes back as an invalid grant, which reads to the user as a dead credential rather
 * than as a line that went to the wrong place.
 */
class CredentialKindTest {

    // Shaped like the real thing, deliberately not real.
    private static final String REFRESH = "M.C500_EXAMPLE.0.U.NotARealToken";
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJuYW1lIjoiQWxleCJ9.sig";

    @Test
    void recognisesARefreshToken() {
        assertEquals(CredentialKind.REFRESH_TOKEN, CredentialKind.detect(REFRESH));
        assertEquals(CredentialKind.REFRESH_TOKEN, CredentialKind.detect("listedname:" + REFRESH));
        assertEquals(CredentialKind.REFRESH_TOKEN, CredentialKind.detect("Bearer " + REFRESH));
        assertEquals(CredentialKind.REFRESH_TOKEN, CredentialKind.detect("\"" + REFRESH + "\""));
        assertEquals(CredentialKind.REFRESH_TOKEN, CredentialKind.detect("  " + REFRESH + "  "));
    }

    @Test
    void recognisesASessionToken() {
        assertEquals(CredentialKind.SESSION_TOKEN, CredentialKind.detect(JWT));
        assertEquals(CredentialKind.SESSION_TOKEN, CredentialKind.detect("listedname:" + JWT));
        assertEquals(CredentialKind.SESSION_TOKEN, CredentialKind.detect("Bearer " + JWT));
    }

    @Test
    void recognisesCookieTextInEveryShapeTheRouteReads() {
        assertEquals(
                CredentialKind.COOKIE_TEXT, CredentialKind.detect(".login.live.com\tTRUE\t/\tTRUE\t0\tMSPOK\tabc123"));
        assertEquals(
                CredentialKind.COOKIE_TEXT,
                CredentialKind.detect("[{\"domain\":\".login.live.com\",\"name\":\"MSPOK\",\"value\":\"abc\"}]"));
    }

    @Test
    void recognisesABareUsername() {
        assertEquals(CredentialKind.OFFLINE_NAME, CredentialKind.detect("Steve"));
        assertEquals(CredentialKind.OFFLINE_NAME, CredentialKind.detect("Notch_123"));
    }

    /** Anything unplaceable fails its entry rather than being guessed at. */
    @Test
    void refusesToGuess() {
        assertEquals(CredentialKind.UNKNOWN, CredentialKind.detect(null));
        assertEquals(CredentialKind.UNKNOWN, CredentialKind.detect("   "));
        assertEquals(CredentialKind.UNKNOWN, CredentialKind.detect("a-name-far-too-long-to-be-a-username"));
        assertEquals(CredentialKind.UNKNOWN, CredentialKind.detect("user@example.com"));
    }

    @Test
    void aSessionTokenIsNotMistakenForAUsername() {
        assertEquals(CredentialKind.SESSION_TOKEN, CredentialKind.detect(JWT), "length alone must not decide");
    }
}
