import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lol.trq.alts.AltsRuntime;
import lol.trq.alts.auth.AltLoginCallback;
import lol.trq.alts.auth.MicrosoftAuthConfig;
import lol.trq.alts.bulk.BulkEntryResult;
import lol.trq.alts.bulk.BulkHandle;
import lol.trq.alts.bulk.BulkOptions;
import lol.trq.alts.bulk.BulkProgress;
import lol.trq.alts.bulk.BulkReport;
import lol.trq.alts.crypto.CryptoException;
import lol.trq.alts.crypto.VaultIdentity;
import lol.trq.alts.crypto.X25519HkdfAesGcmKeyWrap;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.GameStats;
import lol.trq.alts.model.LoginMode;
import lol.trq.alts.model.SessionData;
import lol.trq.alts.net.NetworkScope;
import lol.trq.alts.net.ProxyRoute;
import lol.trq.alts.spi.ProxyProvider;
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
            // Lazy, cached avatar. Keyed by UUID, so a renamed account keeps its head; null until the
            // background fetch lands.
            MyHandle head = alts.skinCache().get(account.uuid());

            // Lazy, cached per-server game stats (null if no source for that server, or fetch pending).
            GameStats stats = alts.gameStats("example.net").get(account.uuid());

            renderCard(head, stats);
        }
    }

    /**
     * Section 3: telling the user their accounts did not load, rather than showing them an empty list.
     * The store's key is derived from machine properties, so an ordinary environment change can make a
     * good file unreadable — and that is not the same thing as having no accounts.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     */
    public static void warnIfTheStoreDidNotLoad(AltsRuntime<MyHandle> alts) {
        AltStore.loadError()
                .ifPresent(reason -> alts.toasts()
                        .toast(
                                ToastSink.Level.ERROR,
                                "Accounts not loaded",
                                "The saved file could not be read: " + reason,
                                8000));
    }

    /**
     * Section "Routing requests": one proxy per account, so a sweep over fifty alts does not arrive at
     * the service as one machine. The provider is asked per request; anything it cannot answer fails the
     * request rather than falling back to the real address.
     *
     * @param poolByUuid the host's own mapping of account to proxy
     * @return the provider to install on the builder
     */
    public static ProxyProvider proxyPerAccount(Map<String, ProxyRoute> poolByUuid) {
        return scope -> {
            // Avatars are not worth a proxy slot, and they carry no credential.
            if (scope.purpose() == NetworkScope.Purpose.AVATAR) {
                return ProxyRoute.direct();
            }
            ProxyRoute route = scope.accountUuid() == null ? null : poolByUuid.get(scope.accountUuid());
            // Returning null would fail the request. Say so explicitly when direct is what you meant.
            return route != null ? route : ProxyRoute.direct();
        };
    }

    /**
     * Section "Checking accounts without logging in": a sweep over everything the store holds. The live
     * session never moves, which is the whole reason this is not a loop over {@code loginAccount}.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     */
    public static void sweepStoredAccounts(AltsRuntime<MyHandle> alts) {
        for (AltAccount account : AltStore.accounts()) {
            alts.accountService().refresh(account).thenAccept(status -> {
                switch (status.state()) {
                    // Nothing to do; the account is good, and RENEWED already persisted its new token.
                    case VALID, RENEWED -> render(status.account().username());
                    // The stored token is spent but recoverable — only a read-only check reports this.
                    case EXPIRED -> render(status.account().username() + " needs refreshing");
                    // The credential is gone for good. Only a fresh interactive login fixes it.
                    case REAUTH_REQUIRED -> promptMicrosoftLogin(status.account());
                    // Authenticated fine, but there is no Minecraft profile behind it.
                    case NOT_ENTITLED -> showError(status.account().username() + " does not own Minecraft");
                    // Try again later. Nothing was spent.
                    case UNREACHABLE -> showRetry(status.message());
                    default -> showError(status.message());
                }
            });
        }
    }

    /**
     * Section "Doing it to every account at once": a paced sweep with progress and a cancel handle. The
     * report separates a run that was stopped from one that merely had failures, because those need
     * different things said to the user.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     * @return the handle, so the caller can cancel it
     */
    public static BulkHandle refreshEverything(AltsRuntime<MyHandle> alts) {
        BulkHandle handle = alts.bulk().refreshAll(AltStore.accounts(), BulkOptions.defaults(), new BulkProgress() {
            @Override
            public void started(int index, int total, String label) {
                render("checking " + label + " (" + (index + 1) + "/" + total + ")");
            }

            @Override
            public void completed(int index, int total, BulkEntryResult result) {
                if (!result.success()) {
                    showError(result.label() + ": " + result.message());
                }
            }

            @Override
            public void finished(BulkReport report) {
                if (report.stoppedEarly()) {
                    showRetry("Stopped early — the service asked us to slow down.");
                }
                render(report.succeeded() + " of " + report.results().size() + " refreshed");
            }
        });
        // handle.cancel() stops it starting anything further; whatever is in flight still finishes.
        return handle;
    }

    /**
     * Section "Doing it to every account at once": importing a pasted list. Each line is classified and
     * sent to the route that fits it; nothing is logged into.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     * @param pastedLines one credential per line, as the user pasted them
     */
    public static void importPastedCredentials(AltsRuntime<MyHandle> alts, List<String> pastedLines) {
        alts.bulk()
                .importCredentials(pastedLines, LoginMode.ADD, BulkOptions.defaults(), BulkProgress.NONE)
                .report()
                .thenAccept(report -> {
                    render(report.succeeded() + " imported");
                    for (BulkEntryResult failure : report.failures()) {
                        // The label is a username or "entry 4" — never the line, which is a credential.
                        showError(failure.label() + ": " + failure.message());
                    }
                });
    }

    /**
     * Section "Logging in from a cookie file": handing the library a path the user picked. The read runs
     * off the calling thread, so this is safe to call straight from a file-picker callback on the render
     * thread, and an unreadable file comes back as a failed result rather than as a thrown exception.
     *
     * @param alts the runtime built by {@link #buildRuntime}
     * @param chosen the cookie file the user picked
     */
    public static void logInFromCookieFile(AltsRuntime<MyHandle> alts, Path chosen) {
        alts.loginService().loginCookieFile(chosen, LoginMode.ADD).thenAccept(result -> {
            if (result.success()) {
                render(result.account().username());
            } else {
                showError(result.message());
            }
        });
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
