import java.nio.file.Path;
import java.util.List;
import lol.trq.alts.AltsRuntime;
import lol.trq.alts.auth.AltLoginCallback;
import lol.trq.alts.auth.MicrosoftAuthConfig;
import lol.trq.alts.crypto.CryptoException;
import lol.trq.alts.crypto.VaultIdentity;
import lol.trq.alts.crypto.X25519HkdfAesGcmKeyWrap;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.GameStats;
import lol.trq.alts.model.LoginMode;
import lol.trq.alts.model.SessionData;
import lol.trq.alts.spi.ToastSink;
import lol.trq.alts.store.AltStore;
import lol.trq.alts.vault.SharedVault;

/**
 * The compiled companion to {@code docs/GETTING_STARTED.md}. Every snippet that guide shows is a method
 * here, so an API change breaks the build instead of quietly rotting the guide: this file is the
 * authoritative copy and the guide inlines from it.
 *
 * <p>Each method maps to one section of the guide and is illustrative only — nothing here is executed.
 * The {@code renderCard} / {@code showError} style helpers at the bottom stand in for the host's own UI.
 *
 * <p>This file is compiled by the {@code examples} source set (see build.gradle) and is not part of the
 * published jar.
 */
public final class GettingStartedExample {

    /**
     * Stand-in for the host renderer's opaque texture-handle type, written {@code MyHandle} throughout
     * the guide. The library never inspects it.
     *
     * @param id the host's texture id
     */
    public record MyHandle(int id) {}

    private GettingStartedExample() {}

    /**
     * Section 3: constructs the one runtime, wiring every host seam.
     *
     * @param dataDir the directory the encrypted account file lives in
     * @param azureClientId the host's own Azure application id; the library ships no default
     * @return the constructed runtime
     */
    public static AltsRuntime<MyHandle> buildRuntime(Path dataDir, String azureClientId) {
        return new AltsRuntime.Builder<MyHandle>()
                .sessionInjector(GettingStartedExample::installSession)
                .vaultDirectory(() -> dataDir)
                .textureUploader(imageBytes -> new MyHandle(0))
                .mainThread(Runnable::run)
                .toastSink((level, title, description, durationMs) -> show(level, title, description))
                .gameStatsSource(new ExampleNetGameStatsSource()) // optional, one per server
                // Required only for Microsoft login. Without it, Microsoft login fails cleanly and
                // offline / cookie / session login still work.
                .microsoftAuth(MicrosoftAuthConfig.of(azureClientId))
                // Optional: only if migrating an existing store written under a different filename /
                // key-binding, pass the legacy values so old files keep loading.
                .storeFileName("accounts.dat")
                .storeKeyBinding("your.mod.id")
                .build();
    }

    /**
     * Section 4: logs in interactively, then reads the stored list, an avatar, and per-server stats.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     */
    public static void logInAndRead(AltsRuntime<MyHandle> alts) {
        // Log in (adds to the stored list and switches session).
        alts.loginService().loginMicrosoft(LoginMode.ADD).thenAccept(result -> {
            if (result.success()) {
                // result.account() is now the current account.
                render(result.account().username());
            }
        });

        // Stored accounts.
        List<AltAccount> saved = AltStore.accounts();

        for (AltAccount account : saved) {
            // Lazy, cached avatar. Keyed by USERNAME, not UUID; null until the background fetch lands.
            MyHandle head = alts.skinCache().get(account.username());

            // Lazy, cached per-server game stats (null if no source for that server, or fetch pending).
            GameStats stats = alts.gameStats("example.net").get(account.uuid());

            renderCard(head, stats);
        }
    }

    /**
     * Section "Refresh tokens and silent renewal": logging into a stored account renews it silently, so
     * the caller drives nothing.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     * @param account a stored account, for example one out of {@link AltStore#accounts()}
     */
    public static void logInStoredAccount(AltsRuntime<MyHandle> alts, AltAccount account) {
        // Reuses the stored session if it is still live; otherwise renews from the refresh token,
        // persists the rotated credential, and installs the session — all without a browser.
        alts.loginService().loginAccount(account).thenAccept(result -> {
            if (result.success()) {
                render(result.account().username());
            }
        });
    }

    /**
     * Section "Refresh tokens and silent renewal": importing a refresh token the host already holds. The
     * token endpoint rotates the credential, so the rotated value is read back off the result.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     * @param refreshToken the OAuth refresh token to redeem
     */
    public static void importRefreshToken(AltsRuntime<MyHandle> alts, String refreshToken) {
        alts.loginService().loginRefreshToken(refreshToken, LoginMode.ADD).thenAccept(result -> {
            if (result.success()) {
                // Always the rotated value; persist it for any account the store does not hold.
                persist(result.account().refreshToken());
            } else if (result.reason() == AltLoginCallback.FailureReason.NOT_CONFIGURED) {
                // Only the routes that talk to Microsoft can report this one.
                showError("Microsoft login is not configured");
            }
        });
    }

    /**
     * Section "Branching on the failure reason": picking the right prompt from the typed reason instead
     * of matching on a message string, which survives neither obfuscation nor localization.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     * @param account the stored account being logged into
     */
    public static void logInStoredAccountWithBranching(AltsRuntime<MyHandle> alts, AltAccount account) {
        alts.loginService().loginAccount(account).thenAccept(result -> {
            if (result.success()) {
                return;
            }
            switch (result.reason()) {
                // The credential is permanently spent and has been discarded. Send the user
                // through a fresh interactive login.
                case REAUTH_REQUIRED -> promptMicrosoftLogin(account);
                // Transient: the service was unreachable or failed. The stored refresh token is
                // untouched, so offer a retry.
                case NETWORK -> showRetry(result.message());
                default -> showError(result.message());
            }
        });
    }

    /**
     * Section "Sharing policy": creating a repository that withholds refresh tokens (the default) and
     * one that shares them with every member. Both overloads throw the checked {@link CryptoException},
     * as does creating the identity.
     *
     * @param passphrase the passphrase sealing the new identity's private material
     * @param alts the initial alt payload
     * @return the repository that shares refresh tokens
     * @throws CryptoException if identity creation, key generation, or encryption fails
     */
    public static SharedVault.CreatedRepo createRepositories(char[] passphrase, List<AltAccount> alts)
            throws CryptoException {
        // The facade is stateless; the identity is the member's key pair, created once here and
        // unlocked from its stored form on later runs.
        SharedVault vault = new SharedVault(new X25519HkdfAesGcmKeyWrap());
        VaultIdentity identity = VaultIdentity.create(passphrase);

        // Withholds refresh tokens (the default).
        SharedVault.CreatedRepo repo = vault.createRepo(identity, alts);
        render(repo.manifest().repoId());

        // Shares them with every member of this repository.
        return vault.createRepo(identity, alts, true);
    }

    /**
     * Section "Game stats": reading one server's chips back off the runtime. The source itself is
     * {@link ExampleNetGameStatsSource}.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     * @param playerUuid the dashed UUID to look up
     */
    public static void readGameStats(AltsRuntime<MyHandle> alts, String playerUuid) {
        GameStats stats = alts.gameStats("example.net").get(playerUuid);
        if (stats != null) {
            for (GameStats.Stat chip : stats.stats()) {
                render(chip.label() + " " + chip.value());
            }
        }
    }

    /** Stands in for the host translating a {@link SessionData} into its platform's live session. */
    private static void installSession(SessionData session) {}

    /** Stands in for the host's notification UI. */
    private static void show(ToastSink.Level level, String title, String description) {}

    /** Stands in for the host drawing a line of text. */
    private static void render(String text) {}

    /** Stands in for the host drawing one account card. */
    private static void renderCard(MyHandle head, GameStats stats) {}

    /** Stands in for the host writing a credential wherever that account actually lives. */
    private static void persist(String refreshToken) {}

    /** Stands in for the host opening a fresh interactive Microsoft login. */
    private static void promptMicrosoftLogin(AltAccount account) {}

    /** Stands in for the host offering a retry. */
    private static void showRetry(String message) {}

    /** Stands in for the host reporting an error. */
    private static void showError(String message) {}
}
