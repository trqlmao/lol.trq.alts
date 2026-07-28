package lol.trq.alts.auth;

import java.nio.file.Path;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.store.AltStore;

/** Binds the static {@link AltStore} to a scratch directory so login tests can assert on persistence. */
final class AltStoreTestSupport {

    private AltStoreTestSupport() {}

    static void bindTo(Path directory) {
        AltStore.bind(() -> directory);
        AltStore.accounts().clear();
        AltStore.useAccount(null);
    }

    static void seed(AltAccount account) {
        AltStore.addAccount(account);
    }

    /**
     * Re-reads the encrypted store from disk before looking the account up, so an assertion can tell a
     * persisted change from one that only ever touched the in-memory list.
     */
    static AltAccount reloadFromDiskAndFind(String uuid) {
        AltStore.accounts().clear();
        AltStore.load();
        return find(uuid);
    }

    static AltAccount find(String uuid) {
        return AltStore.accounts().stream()
                .filter(a -> a.uuid().equals(uuid))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stored account with uuid " + uuid));
    }
}
