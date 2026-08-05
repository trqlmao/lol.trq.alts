package lol.trq.alts.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A store file that cannot be read is not an empty store, and the two must never be conflated. The
 * encryption key is derived from machine properties, so a renamed OS user or a moved home directory
 * turns a perfectly good file unreadable — at which point saving anything at all would write an empty
 * list over every account the user had.
 */
class AltStoreUnreadableFileTest {

    private static final String FILE_NAME = "accounts.dat";
    private static final String UUID = "00000000-0000-4000-8000-00000000000a";

    @TempDir
    Path storeDir;

    @BeforeEach
    void bindStore() {
        AltStore.bind(() -> storeDir);
        AltStore.accounts().clear();
        AltStore.useAccount(null);
    }

    private Path storeFile() {
        return storeDir.resolve(FILE_NAME);
    }

    private Path backupFile() {
        return storeDir.resolve(FILE_NAME + ".unreadable");
    }

    @Test
    void anAbsentFileIsNotAFailure() {
        AltStore.load();

        assertTrue(AltStore.loadError().isEmpty(), "there is nothing wrong with a store that was never written");
    }

    @Test
    void aReadableFileClearsAnEarlierFailure() throws Exception {
        Files.writeString(storeFile(), "not an encrypted store");
        AltStore.load();
        assertTrue(AltStore.loadError().isPresent());

        AltStore.accounts().clear();
        AltStore.addAccount(AltAccount.of(UUID, "Alex", "access", AccountType.MICROSOFT));
        AltStore.load();

        assertTrue(AltStore.loadError().isEmpty(), "a load that works must retract the earlier failure");
    }

    @Test
    void anUnreadableFileReportsTheFailureAndKeepsTheInMemoryList() throws Exception {
        AltStore.accounts().add(AltAccount.of(UUID, "Alex", "access", AccountType.MICROSOFT));
        Files.writeString(storeFile(), "not an encrypted store");

        AltStore.load();

        assertTrue(AltStore.loadError().isPresent(), "an unreadable file must be reported, not swallowed");
        assertEquals(1, AltStore.accounts().size(), "a failed load must not clear what is already held");
    }

    @Test
    void savingOverAnUnreadableFileKeepsACopyOfIt() throws Exception {
        byte[] original = "not an encrypted store".getBytes(StandardCharsets.UTF_8);
        Files.write(storeFile(), original);
        AltStore.load();

        AltStore.addAccount(AltAccount.of(UUID, "Alex", "access", AccountType.MICROSOFT));

        assertTrue(Files.exists(backupFile()), "the unreadable file must survive the save that replaced it");
        assertArrayEquals(original, Files.readAllBytes(backupFile()), "the copy must be the original bytes");
        assertTrue(Files.size(storeFile()) > 0, "the store itself is rewritten with the current list");
    }

    @Test
    void aSecondFailureDoesNotOverwriteTheFirstBackup() throws Exception {
        byte[] original = "the file that held the accounts".getBytes(StandardCharsets.UTF_8);
        Files.write(storeFile(), original);
        AltStore.load();
        AltStore.addAccount(AltAccount.of(UUID, "Alex", "access", AccountType.MICROSOFT));

        // A later run finds the freshly written store unreadable too, and saves again.
        Files.writeString(storeFile(), "unreadable again");
        AltStore.load();
        AltStore.save();

        assertArrayEquals(
                original,
                Files.readAllBytes(backupFile()),
                "the oldest backup is the one holding the accounts and must not be replaced");
    }
}
