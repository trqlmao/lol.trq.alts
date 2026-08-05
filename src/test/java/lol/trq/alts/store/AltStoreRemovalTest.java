package lol.trq.alts.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Removal keys on the UUID like every other mutator here. An account is a record, so equality covers
 * its credentials too — and a caller almost always holds a copy read before some login rotated them.
 */
class AltStoreRemovalTest {

    private static final String UUID = "00000000-0000-4000-8000-00000000000b";

    @TempDir
    Path storeDir;

    @BeforeEach
    void bindStore() {
        AltStore.bind(() -> storeDir);
        AltStore.accounts().clear();
        AltStore.useAccount(null);
    }

    @Test
    void aStaleCopyStillRemovesTheStoredAccount() {
        AltAccount stale = AltAccount.of(UUID, "Alex", "old-access", AccountType.MICROSOFT);
        AltStore.addAccount(stale);
        AltStore.updateCredentials(stale.withTokens("rotated-access", "rotated-refresh", 5_000L));

        AltStore.removeAccount(stale);

        assertTrue(AltStore.accounts().isEmpty(), "the entry is identified by its UUID, not by its credentials");
    }

    @Test
    void removingTheCurrentAccountClearsIt() {
        AltAccount account = AltAccount.of(UUID, "Alex", "access", AccountType.MICROSOFT);
        AltStore.addAccount(account);
        AltStore.useAccount(account);

        // useAccount stamps a fresh lastUsed, so what the store holds current is no longer this instance.
        AltStore.removeAccount(account);

        assertTrue(AltStore.currentAccount().isEmpty(), "the removed account must not stay current");
    }

    @Test
    void removingOneAccountLeavesTheOthers() {
        AltAccount kept = AltAccount.of("00000000-0000-4000-8000-00000000000c", "Steve", "a", AccountType.OFFLINE);
        AltStore.addAccount(AltAccount.of(UUID, "Alex", "b", AccountType.OFFLINE));
        AltStore.addAccount(kept);

        AltStore.removeAccount(AltAccount.of(UUID, "Alex", "different-token", AccountType.OFFLINE));

        assertEquals(1, AltStore.accounts().size());
        assertEquals("Steve", AltStore.accounts().get(0).username());
    }
}
