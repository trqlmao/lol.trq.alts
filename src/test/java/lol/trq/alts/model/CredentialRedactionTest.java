package lol.trq.alts.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lol.trq.alts.auth.AltLoginCallback;
import lol.trq.alts.auth.MinecraftProfile;
import org.junit.jupiter.api.Test;

/**
 * A host that logs a model record writes whatever that record's {@code toString} emits to a durable log
 * file. Credentials must never be part of that; identity must, or the log is useless.
 */
class CredentialRedactionTest {

    private static final String ACCESS = "access-token-value-do-not-log";
    private static final String REFRESH = "refresh-token-value-do-not-log";

    private static AltAccount account() {
        return AltAccount.of("00000000-0000-4000-8000-000000000001", "Alex", ACCESS, AccountType.MICROSOFT)
                .withTokens(ACCESS, REFRESH, 1_800_000_000_000L);
    }

    @Test
    void anAltAccountPrintsItsIdentityButNeitherToken() {
        String printed = account().toString();

        assertFalse(printed.contains(ACCESS), "the access token must not reach a log: " + printed);
        assertFalse(printed.contains(REFRESH), "the refresh token must not reach a log: " + printed);
        assertTrue(printed.contains("00000000-0000-4000-8000-000000000001"), printed);
        assertTrue(printed.contains("Alex"), printed);
        assertTrue(printed.contains("MICROSOFT"), printed);
    }

    @Test
    void aLoginResultDoesNotLeakTheAccountItCarries() {
        String printed = AltLoginCallback.LoginResult.success(account()).toString();

        assertFalse(printed.contains(ACCESS), "logging a result must not write a credential: " + printed);
        assertFalse(printed.contains(REFRESH), "logging a result must not write a credential: " + printed);
        assertTrue(printed.contains("Alex"), printed);
    }

    @Test
    void sessionDataPrintsItsIdentityButNotItsToken() {
        String printed = new SessionData("Alex", "00000000-0000-4000-8000-000000000001", ACCESS, AccountType.MICROSOFT)
                .toString();

        assertFalse(printed.contains(ACCESS), "the session token must not reach a log: " + printed);
        assertTrue(printed.contains("Alex"), printed);
        assertTrue(printed.contains("MICROSOFT"), printed);
    }

    @Test
    void aMinecraftProfilePrintsItsIdentityButNeitherToken() {
        String printed = new MinecraftProfile(
                        "Alex", "00000000-0000-4000-8000-000000000001", ACCESS, REFRESH, 1_800_000_000_000L)
                .toString();

        assertFalse(printed.contains(ACCESS), "the access token must not reach a log: " + printed);
        assertFalse(printed.contains(REFRESH), "the refresh token must not reach a log: " + printed);
        assertTrue(printed.contains("Alex"), printed);
    }
}
