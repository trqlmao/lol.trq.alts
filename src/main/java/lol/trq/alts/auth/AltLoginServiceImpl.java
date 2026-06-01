package lol.trq.alts.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.LoginMode;
import lol.trq.alts.model.SessionData;
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

    /**
     * Creates a login service that installs resolved sessions through the given injector.
     *
     * @param sessionInjector the host hook that makes a resolved account the active session
     * @param microsoftAuth the host's Microsoft authentication configuration, or {@code null} to disable
     *     Microsoft login (offline / cookie / session login stay available)
     */
    public AltLoginServiceImpl(SessionInjector sessionInjector, MicrosoftAuthConfig microsoftAuth) {
        this.sessionInjector = Objects.requireNonNull(sessionInjector, "sessionInjector");
        this.microsoftAuth = microsoftAuth;
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
                return AltLoginCallback.LoginResult.failure("Token empty");
            }

            String cleanToken = cleanToken(sessionToken);

            // Optimization: try to extract identity from the JWT payload without a network request
            if (cleanToken.startsWith("eyJ")) {
                Optional<AltLoginCallback.LoginResult> fastResult = attemptFastJwtLogin(cleanToken, mode);
                if (fastResult.isPresent()) return fastResult.get();
            }

            // Fallback: perform an API request to validate the token and get profile data
            try {
                String[] profile = AccountNetworkUtil.fetchProfileFromToken(cleanToken);
                if (profile == null) throw new Exception("Invalid token or session expired");
                return finalizeLogin(profile[0], profile[1], cleanToken, AccountType.SESSION, mode);
            } catch (Exception e) {
                return AltLoginCallback.LoginResult.failure("Login failed: " + e.getMessage());
            }
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
                String cleanName = name.trim().replaceAll("[^a-zA-Z0-9_]", "");
                if (cleanName.isEmpty() || cleanName.length() > 16) {
                    return AltLoginCallback.LoginResult.failure("Invalid username length (1-16 chars)");
                }

                UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + cleanName).getBytes(StandardCharsets.UTF_8));
                return finalizeLogin(cleanName, uuid.toString(), "", AccountType.OFFLINE, mode);
            } catch (Exception e) {
                return AltLoginCallback.LoginResult.failure("Error: " + e.getMessage());
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
            return CompletableFuture.completedFuture(
                    AltLoginCallback.LoginResult.failure("Microsoft login not configured"));
        }
        return MicrosoftAuthUtil.authenticate(microsoftAuth)
                .thenApply(profile -> finalizeLogin(
                        profile.username(), profile.uuid(), profile.accessToken(), AccountType.MICROSOFT, mode))
                .exceptionally(ex -> {
                    String msg = ex.getMessage();
                    if (ex.getCause() != null) msg = ex.getCause().getMessage();
                    return AltLoginCallback.LoginResult.failure("Microsoft Auth: " + msg);
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
                    return AltLoginCallback.LoginResult.failure("Cookie data empty");

                MinecraftProfile profile = CookieAuthUtil.authenticate(cookieData);
                return finalizeLogin(
                        profile.username(), profile.uuid(), profile.accessToken(), AccountType.COOKIE, mode);
            } catch (Exception e) {
                String msg = e.getMessage();
                return AltLoginCallback.LoginResult.failure("Cookie Auth: " + (msg != null ? msg : "Unknown error"));
            }
        });
    }

    /**
     * Authenticates into a pre-existing {@link AltAccount}.
     *
     * @param account the account model to log into
     * @return a future containing the result of the login attempt
     */
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginAccount(AltAccount account) {
        if (account.type() == AccountType.OFFLINE) {
            return loginOffline(account.username(), LoginMode.DIRECT);
        }
        return loginSession(account.accessToken(), LoginMode.DIRECT);
    }

    /**
     * Finalizes the login process by installing the resolved session through the host injector and
     * updating local storage.
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

            sessionInjector.inject(
                    new SessionData(account.username(), account.uuid(), account.accessToken(), account.type()));

            if (mode == LoginMode.ADD) AltStore.addAccount(account);
            AltStore.useAccount(account);

            return AltLoginCallback.LoginResult.success(account);
        } catch (Exception e) {
            return AltLoginCallback.LoginResult.failure("Session Injection: " + e.getMessage());
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
     * Sanitizes a token string by removing prefixes and whitespace.
     *
     * @param t the raw token
     * @return the sanitized token
     */
    private String cleanToken(String t) {
        t = t.trim();
        if (t.contains(":")) {
            for (String p : t.split(":")) if (p.startsWith("eyJ")) return p;
        }
        return t.toLowerCase().startsWith("bearer ") ? t.substring(7).trim() : t;
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
