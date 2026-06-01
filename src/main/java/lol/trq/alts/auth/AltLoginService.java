package lol.trq.alts.auth;

import java.util.concurrent.CompletableFuture;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.LoginMode;

/**
 * Contract for Minecraft account authentication services. Provides asynchronous methods to log into
 * accounts via Microsoft OAuth, browser cookies, session tokens, or offline (cracked) identities.
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
     * Authenticates using a pre-existing Minecraft access token or session string.
     *
     * @param sessionToken the raw access token or session string
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     */
    CompletableFuture<AltLoginCallback.LoginResult> loginSession(String sessionToken, LoginMode mode);

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
