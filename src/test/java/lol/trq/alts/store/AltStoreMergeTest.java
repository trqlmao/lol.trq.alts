package lol.trq.alts.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.BanInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Re-adding an alt the store already holds must refresh its credentials, not reset what it knows. */
class AltStoreMergeTest {

    private static final String UUID = "00000000-0000-4000-8000-000000000009";

    @TempDir
    Path storeDir;

    @BeforeEach
    void bindStore() {
        AltStore.bind(() -> storeDir);
        AltStore.accounts().clear();
        AltStore.useAccount(null);
    }

    private static AltAccount richStoredAccount() {
        return AltAccount.of(UUID, "Alex", "old-access", AccountType.MICROSOFT)
                .withTokens("old-access", "old-refresh", 1_000L)
                .withBan("serverone", BanInfo.observed("self", "kicked"))
                .withSource("democlient", "user1")
                .usedNow("member-one");
    }

    @Test
    void reAddingAnAccountMergesOntoTheStoredRecord() {
        AltStore.addAccount(richStoredAccount());

        AltStore.addAccount(AltAccount.of(UUID, "Alex", "new-access", AccountType.MICROSOFT)
                .withTokens("new-access", "new-refresh", 2_000L));

        assertEquals(1, AltStore.accounts().size(), "the account is still stored once");
        AltAccount stored = AltStore.accounts().get(0);
        assertEquals("new-access", stored.accessToken(), "the fresh credential must land");
        assertEquals("new-refresh", stored.refreshToken(), "the rotated credential must land");
        assertEquals(2_000L, stored.expiresAt(), "the expiry must describe the token now held");
        assertTrue(stored.banned("serverone"), "re-adding must not erase observed bans");
        assertEquals("democlient", stored.sourceClient(), "re-adding must not erase provenance");
        assertEquals("user1", stored.sourceUser(), "re-adding must not erase provenance");
        assertEquals("member-one", stored.lastUsedBy(), "re-adding must not erase shared attribution");
    }

    @Test
    void aRouteThatIssuesNoRefreshTokenKeepsTheStoredOne() {
        AltStore.addAccount(richStoredAccount());

        AltStore.addAccount(AltAccount.of(UUID, "Alex", "cookie-access", AccountType.COOKIE));

        AltAccount stored = AltStore.accounts().get(0);
        assertEquals("cookie-access", stored.accessToken());
        assertEquals(AccountType.COOKIE, stored.type(), "the route that just authenticated defines the type");
        assertEquals("old-refresh", stored.refreshToken(), "a route with no refresh token must not clear one");
    }

    @Test
    void anUnknownAccountIsStoredAsGiven() {
        AltAccount fresh = AltAccount.of(UUID, "Alex", "access", AccountType.MICROSOFT);

        AltStore.addAccount(fresh);

        assertEquals(1, AltStore.accounts().size());
        assertEquals("access", AltStore.accounts().get(0).accessToken());
    }
}
