package lol.trq.alts.auth;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.LoginMode;

/**
 * Contract for Minecraft account authentication services. Provides asynchronous methods to log into
 * accounts via Microsoft OAuth, browser cookies, session tokens, refresh tokens, or offline (cracked)
 * identities.
 *
 * @author trq
 * @since 0.1.0
 */
public interface AltLoginService {

    /**
     * Initiates authentication through the official Microsoft OAuth2 flow.
     *
     * @param mode the login mode (whether to add the account to storage)
     * @return a future containing the result of the login attempt
     */
    CompletableFuture<AltLoginCallback.LoginResult> loginMicrosoft(LoginMode mode);

    /**
     * Authenticates using raw browser cookie data. Useful for bypassing 2FA if cookies are exported
     * from an authenticated browser.
     *
     * @param cookieData the cookie string in Netscape or mangled format
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     */
    CompletableFuture<AltLoginCallback.LoginResult> loginCookie(String cookieData, LoginMode mode);

    /**
     * Authenticates using cookies exported to a file, which is the shape browser extensions produce
     * them in. The file is read off the calling thread; decoding and parsing are handled for the
     * caller, so a host only has to supply a path.
     *
     * @param file the exported cookie file, in Netscape or mangled format
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     * @since 0.7.0
     */
    CompletableFuture<AltLoginCallback.LoginResult> loginCookieFile(Path file, LoginMode mode);

    /**
     * Authenticates using a pre-existing Minecraft access token or session string.
     *
     * @param sessionToken the raw access token or session string
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     */
    CompletableFuture<AltLoginCallback.LoginResult> loginSession(String sessionToken, LoginMode mode);

    /**
     * Authenticates using a stored OAuth refresh token, skipping the interactive browser step. The
     * token endpoint issues a rotated refresh token, which is stored on the resulting account.
     *
     * @param refreshToken the OAuth refresh token to redeem
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     * @since 0.6.0
     */
    CompletableFuture<AltLoginCallback.LoginResult> loginRefreshToken(String refreshToken, LoginMode mode);

    /**
     * Authenticates for offline (cracked) play using only a username.
     *
     * @param username the desired offline username
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     */
    CompletableFuture<AltLoginCallback.LoginResult> loginOffline(String username, LoginMode mode);

    /**
     * Performs a login operation using an existing {@link AltAccount} data model.
     *
     * @param account the account data to authenticate with
     * @return a future containing the result of the login attempt
     */
    CompletableFuture<AltLoginCallback.LoginResult> loginAccount(AltAccount account);
}
