package lol.trq.alts.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.LoginMode;
import lol.trq.alts.model.SessionData;
import lol.trq.alts.net.NetworkScope;
import lol.trq.alts.spi.SessionInjector;
import lol.trq.alts.store.AltStore;

/**
 * Concrete {@link AltLoginService} implementing authentication across protocols (JWT fast-path, Mojang
 * lookups, OAuth2, and browser cookie injection). On success it hands a {@link SessionData} to the
 * host-provided {@link SessionInjector} and updates the local {@link AltStore}; it holds no Minecraft
 * types itself.
 *
 * @author trq
 * @since 0.1.0
 */
public class AltLoginServiceImpl implements AltLoginService {

    private final SessionInjector sessionInjector;
    private final MicrosoftAuthConfig microsoftAuth;
    private final Clock clock;
    private final AltAccountService accounts;

    /**
     * Creates a login service that installs resolved sessions through the given injector.
     *
     * @param sessionInjector the host hook that makes a resolved account the active session
     * @param microsoftAuth the host's Microsoft authentication configuration, or {@code null} to disable
     *     Microsoft login (offline / cookie / session login stay available)
     */
    public AltLoginServiceImpl(SessionInjector sessionInjector, MicrosoftAuthConfig microsoftAuth) {
        this(sessionInjector, microsoftAuth, Clock.systemUTC());
    }

    // Exists so expiry-sensitive behaviour can be exercised against a fixed clock in tests.
    AltLoginServiceImpl(SessionInjector sessionInjector, MicrosoftAuthConfig microsoftAuth, Clock clock) {
        this.sessionInjector = Objects.requireNonNull(sessionInjector, "sessionInjector");
        this.microsoftAuth = microsoftAuth;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.accounts = new AltAccountServiceImpl(microsoftAuth, this.clock);
    }

    /**
     * Returns the account service this login service is built on, for a caller that wants to validate or
     * renew without installing a session.
     *
     * @return the account service
     * @since 0.8.0
     */
    public AltAccountService accountService() {
        return accounts;
    }

    /**
     * Authenticates a player using a Minecraft access token or session string. Tries to decode the
     * token as a JWT for speed, falling back to a network lookup if the format is unknown.
     *
     * @param sessionToken the raw access token or session string
     * @param mode the login mode (whether to save the account or just log in)
     * @return a future containing the result of the login attempt
     */
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginSession(String sessionToken, LoginMode mode) {
        return CompletableFuture.supplyAsync(() -> {
            if (sessionToken == null || sessionToken.isBlank()) {
                return AltLoginCallback.LoginResult.failure("Token empty", FailureReason.INVALID_TOKEN);
            }

            String cleanToken = cleanToken(sessionToken);

            // Optimization: try to extract identity from the JWT payload without a network request
            if (cleanToken.startsWith("eyJ")) {
                Optional<AltLoginCallback.LoginResult> fastResult = attemptFastJwtLogin(cleanToken, mode);
                if (fastResult.isPresent()) return fastResult.get();
            }

            // Fallback: perform an API request to validate the token and get profile data. A refusal, an
            // account with no Minecraft profile, and an unreachable service are three different answers
            // with three different remedies, so they are classified apart rather than collapsed.
            AccountNetworkUtil.ProfileLookup profile;
            try {
                profile = AccountNetworkUtil.fetchProfile(
                        cleanToken, profileUrl(), NetworkScope.of(NetworkScope.Purpose.PROFILE));
            } catch (Exception unreachable) {
                return AltLoginCallback.LoginResult.failure(
                        "Login failed: " + unreachable.getMessage(), FailureReason.NETWORK);
            }
            if (profile.found()) {
                return finalizeLogin(profile.username(), profile.uuid(), cleanToken, AccountType.SESSION, mode);
            }
            return AltLoginCallback.LoginResult.failure(
                    AltAccountServiceImpl.messageFor(profile), AltAccountServiceImpl.reasonFor(profile));
        });
    }

    /**
     * Authenticates a player for offline play using a simple username, generating an offline-mode UUID.
     *
     * @param name the desired username
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     */
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginOffline(String name, LoginMode mode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String cleanName = name == null ? "" : name.trim().replaceAll("[^a-zA-Z0-9_]", "");
                if (cleanName.isEmpty() || cleanName.length() > 16) {
                    return AltLoginCallback.LoginResult.failure(
                            "Invalid username length (1-16 chars)", FailureReason.INVALID_TOKEN);
                }

                UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + cleanName).getBytes(StandardCharsets.UTF_8));
                return finalizeLogin(cleanName, uuid.toString(), "", AccountType.OFFLINE, mode);
            } catch (Exception e) {
                return AltLoginCallback.LoginResult.failure("Error: " + e.getMessage(), FailureReason.UNKNOWN);
            }
        });
    }

    /**
     * Initiates the Microsoft OAuth2 authentication flow via a local callback server.
     *
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     */
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginMicrosoft(LoginMode mode) {
        if (microsoftAuth == null) {
            return CompletableFuture.completedFuture(AltLoginCallback.LoginResult.failure(
                    "Microsoft login not configured", FailureReason.NOT_CONFIGURED));
        }
        return MicrosoftAuthUtil.authenticate(microsoftAuth)
                .thenApply(profile -> finalizeLogin(profile, AccountType.MICROSOFT, mode))
                .exceptionally(ex -> {
                    String msg = ex.getMessage();
                    if (ex.getCause() != null) msg = ex.getCause().getMessage();
                    return AltLoginCallback.LoginResult.failure("Microsoft Auth: " + msg, FailureReason.UNKNOWN);
                });
    }

    /**
     * Authenticates a player using raw browser cookie data.
     *
     * @param cookieData the cookie string (Netscape or mangled format)
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     */
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginCookie(String cookieData, LoginMode mode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (cookieData == null || cookieData.isBlank())
                    return AltLoginCallback.LoginResult.failure("Cookie data empty", FailureReason.INVALID_TOKEN);

                MinecraftProfile profile = CookieAuthUtil.authenticate(cookieData);
                return finalizeLogin(
                        profile.username(), profile.uuid(), profile.accessToken(), AccountType.COOKIE, mode);
            } catch (Exception e) {
                String msg = e.getMessage();
                return AltLoginCallback.LoginResult.failure(
                        "Cookie Auth: " + (msg != null ? msg : "Unknown error"), FailureReason.INVALID_TOKEN);
            }
        });
    }

    /**
     * Authenticates using cookies read from a file. The read runs off the calling thread so a host can
     * hand over a path straight from a file picker on its render thread, and a file that cannot be read
     * fails the same way bad cookie text does rather than throwing at the caller.
     *
     * @param file the exported cookie file
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     * @since 0.7.0
     */
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginCookieFile(Path file, LoginMode mode) {
        if (file == null) {
            return CompletableFuture.completedFuture(
                    AltLoginCallback.LoginResult.failure("No cookie file given", FailureReason.INVALID_TOKEN));
        }
        return CompletableFuture.supplyAsync(() -> {
                    try {
                        return CookieFile.read(file);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                })
                .thenCompose(cookieData -> loginCookie(cookieData, mode))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    return AltLoginCallback.LoginResult.failure(
                            "Cookie file: " + (cause.getMessage() != null ? cause.getMessage() : "could not be read"),
                            FailureReason.INVALID_TOKEN);
                });
    }

    /**
     * Authenticates using a stored OAuth refresh token, skipping the interactive browser step.
     *
     * @param refreshToken the OAuth refresh token to redeem
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     * @since 0.6.0
     */
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginRefreshToken(String refreshToken, LoginMode mode) {
        if (microsoftAuth == null) {
            return CompletableFuture.completedFuture(AltLoginCallback.LoginResult.failure(
                    "Microsoft login not configured", FailureReason.NOT_CONFIGURED));
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            return CompletableFuture.completedFuture(
                    AltLoginCallback.LoginResult.failure("Refresh token empty", FailureReason.INVALID_TOKEN));
        }
        String cleaned = cleanRefreshToken(refreshToken);
        if (cleaned.isBlank()) {
            return CompletableFuture.completedFuture(
                    AltLoginCallback.LoginResult.failure("Refresh token empty", FailureReason.INVALID_TOKEN));
        }
        return MicrosoftAuthUtil.authenticateWithRefreshToken(microsoftAuth, cleaned)
                .thenApply(profile -> finalizeLogin(profile, AccountType.MICROSOFT, mode))
                .exceptionally(AltLoginServiceImpl::importFailure);
    }

    /**
     * Authenticates into a pre-existing {@link AltAccount}, renewing the session first when its access
     * token is spent and once more if the stored token turns out to be refused.
     *
     * <p>This is {@link AltAccountService#refresh(AltAccount)} plus one step. Making the account usable
     * and installing it as the live session are separate concerns, and separating them is what lets a
     * host validate or renew a hundred accounts without the session moving.
     *
     * @param account the account model to log into
     * @return a future containing the result of the login attempt
     */
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginAccount(AltAccount account) {
        if (account.type() == AccountType.OFFLINE) {
            return loginOffline(account.username(), LoginMode.DIRECT);
        }
        return accounts.refresh(account)
                .thenApply(status -> status.usable()
                        ? inject(status.account())
                        : AltLoginCallback.LoginResult.failure(status.message(), status.reason()));
    }

    /**
     * Installs an already-resolved account as the live session without rebuilding it.
     *
     * @param account the account to install
     * @return the login result
     */
    private AltLoginCallback.LoginResult inject(AltAccount account) {
        try {
            sessionInjector.inject(
                    new SessionData(account.username(), account.uuid(), account.accessToken(), account.type()));
            AltStore.useAccount(account);
            return AltLoginCallback.LoginResult.success(account);
        } catch (Exception e) {
            return AltLoginCallback.LoginResult.failure("Session Injection: " + e.getMessage(), FailureReason.UNKNOWN);
        }
    }

    /**
     * Returns the profile endpoint to validate against — the configured one when Microsoft login is
     * wired up, and the public default otherwise.
     *
     * @return the Minecraft services profile endpoint
     */
    private String profileUrl() {
        return microsoftAuth != null
                ? microsoftAuth.minecraftProfileUrl()
                : MicrosoftAuthConfig.DEFAULT_MINECRAFT_PROFILE_URL;
    }

    /**
     * Maps a failed refresh-token <em>import</em> onto a classified result. Nothing is discarded here:
     * the token came from the caller rather than from the store, so there is no stored credential this
     * failure could cost. Renewing a stored account is {@link AltAccountServiceImpl}'s job, and it does
     * clear a permanently spent one.
     *
     * @param ex the failure the redemption completed with
     * @return the classified failure result
     */
    private static AltLoginCallback.LoginResult importFailure(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        boolean permanent =
                cause instanceof MicrosoftAuthUtil.RefreshRejectedException rejection && rejection.permanent();

        return AltLoginCallback.LoginResult.failure(
                "Refresh: " + (cause.getMessage() != null ? cause.getMessage() : "unknown error"),
                permanent ? FailureReason.REAUTH_REQUIRED : FailureReason.NETWORK);
    }

    /**
     * Finalizes a flow that produced a freshly authenticated account, carrying the issued credentials
     * onto it. When the account is being saved and the store already holds the same alt, the issued
     * credentials are merged onto the stored record, so importing a credential for an alt the user
     * already has refreshes it instead of resetting its bans, provenance, and attribution.
     *
     * @param profile the resolved profile
     * @param type the type of account used
     * @param mode the login mode
     * @return the login result
     */
    private AltLoginCallback.LoginResult finalizeLogin(MinecraftProfile profile, AccountType type, LoginMode mode) {
        AltAccount account = AltAccount.of(formatUuid(profile.uuid()), profile.username(), profile.accessToken(), type)
                .withTokens(profile.accessToken(), profile.refreshToken(), profile.expiresAt());

        if (mode == LoginMode.ADD) {
            account = account.mergedOnto(storedRecord(account.uuid()));
            AltStore.addAccount(account);
        }
        return inject(account);
    }

    /**
     * Returns the record the store holds for {@code uuid}, so a login can be merged onto it instead of
     * replacing it.
     *
     * @param uuid the dashed UUID to look up
     * @return the stored account, or {@code null} when the store holds none for that UUID
     */
    private static AltAccount storedRecord(String uuid) {
        return AltStore.accounts().stream()
                .filter(a -> a.uuid().equals(uuid))
                .findFirst()
                .orElse(null);
    }

    /**
     * Finalizes the login process by installing the resolved session through the host injector and
     * updating local storage. As with the profile-based overload, saving merges onto the stored record
     * for the same alt rather than replacing it.
     *
     * @param username the player's username
     * @param uuid the player's UUID string
     * @param token the authentication token
     * @param type the type of account used
     * @param mode the login mode
     * @return a result object containing success status and the account data
     */
    private AltLoginCallback.LoginResult finalizeLogin(
            String username, String uuid, String token, AccountType type, LoginMode mode) {
        try {
            String fmtUuid = formatUuid(uuid);
            AltAccount account = AltAccount.of(fmtUuid, username, token, type);
            if (mode == LoginMode.ADD) {
                account = account.mergedOnto(storedRecord(fmtUuid));
            }

            sessionInjector.inject(
                    new SessionData(account.username(), account.uuid(), account.accessToken(), account.type()));

            if (mode == LoginMode.ADD) AltStore.addAccount(account);
            AltStore.useAccount(account);

            return AltLoginCallback.LoginResult.success(account);
        } catch (Exception e) {
            return AltLoginCallback.LoginResult.failure("Session Injection: " + e.getMessage(), FailureReason.UNKNOWN);
        }
    }

    /**
     * Attempts to extract a username and UUID from a token without network calls by parsing the JWT
     * payload if applicable.
     *
     * @param token the JWT string
     * @param mode the login mode
     * @return an optional result if the JWT was valid and contained profile data
     */
    private Optional<AltLoginCallback.LoginResult> attemptFastJwtLogin(String token, LoginMode mode) {
        try {
            long expiry = TokenExpiry.jwtExpiryMillis(token);
            if (expiry > 0 && clock.millis() >= expiry - TokenExpiry.SKEW_MILLIS) {
                return Optional.empty();
            }

            String payload = decodeJwtPayload(token);
            String uuid = extractRegex(payload, "\"(?:id|mc)\"\\s*:\\s*\"([a-fA-F0-9\\-]+)\"");
            String username = extractRegex(payload, "\"name\"\\s*:\\s*\"([^\"]+)\"");

            if (uuid != null && username != null) {
                return Optional.of(finalizeLogin(username, uuid, token, AccountType.SESSION, mode));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    /**
     * Decodes the Base64-encoded payload section of a JSON Web Token.
     *
     * @param token the full JWT string
     * @return the decoded JSON payload
     */
    private String decodeJwtPayload(String token) {
        String base64 = token.split("\\.")[1];
        int padding = (4 - base64.length() % 4) % 4;
        base64 = base64.replace('-', '+').replace('_', '/');
        return new String(Base64.getDecoder().decode(base64 + "=".repeat(padding)), StandardCharsets.UTF_8);
    }

    /**
     * Finds the first capture group of {@code regex} within {@code text}.
     *
     * @param text the source text
     * @param regex the regular expression
     * @return the first capture group value, or null if not found
     */
    private String extractRegex(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Sanitizes a pasted refresh token. Tokens are routinely copied out of account lists that prefix
     * them with a name (`user:token`) or out of an HTTP header, and the surrounding text is invisible
     * in a password-style input. Left in place it is sent to the token endpoint as part of the
     * credential, which the service rejects as an invalid grant — a failure that reads to the user as
     * "my token is bad" rather than "my paste was bad".
     *
     * <p>Kept separate from {@link #cleanToken(String)}, which selects the {@code eyJ}-prefixed segment
     * of a JWT and so cannot recognise a refresh token at all.
     *
     * @param raw the pasted token, possibly wrapped in surrounding text
     * @return the token with any name prefix, bearer prefix, quoting, and whitespace removed
     */
    private String cleanRefreshToken(String raw) {
        String t = raw.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            t = t.substring(7).trim();
        }
        if (t.contains(":")) {
            for (String part : t.split(":")) {
                if (part.startsWith("M.")) {
                    return part.trim();
                }
            }
        }
        return t;
    }

    /**
     * Sanitizes a pasted session token by selecting the JWT out of any surrounding text and dropping a
     * bearer prefix.
     *
     * @param t the raw token
     * @return the sanitized token
     */
    private String cleanToken(String t) {
        t = t.trim();
        if (t.contains(":")) {
            for (String p : t.split(":")) if (p.startsWith("eyJ")) return p;
        }
        return t.toLowerCase(Locale.ROOT).startsWith("bearer ") ? t.substring(7).trim() : t;
    }

    /**
     * Formats a raw UUID string by adding dashes if they are missing.
     *
     * @param u the raw UUID string
     * @return a formatted UUID string with dashes
     */
    private String formatUuid(String u) {
        return u.contains("-")
                ? u
                : u.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                        "$1-$2-$3-$4-$5");
    }
}
