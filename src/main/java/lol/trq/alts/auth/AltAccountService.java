package lol.trq.alts.auth;

import java.util.concurrent.CompletableFuture;
import lol.trq.alts.model.AltAccount;

/**
 * Operating on a stored account without logging into it.
 *
 * <p>Everything here leaves the live session alone. That is the whole point: a "check all my alts"
 * sweep, a background refresh, or a bulk import cannot be built on {@link AltLoginService#loginAccount},
 * because that installs each account it touches as the active session — fifty accounts, fifty session
 * switches, and whichever finished last wins.
 *
 * <p>{@link AltLoginService#loginAccount} is this plus one step: {@link #refresh(AltAccount)}, and then
 * the injection.
 *
 * @author trq
 * @since 0.8.0
 */
public interface AltAccountService {

    /**
     * Asks the service whether an account's stored token still works, changing nothing.
     *
     * <p>Never renews and never rotates. That matters more than it looks: the token endpoint issues a
     * new refresh token on every redemption, so a validation sweep built on {@link #refresh} would spend
     * one rotation per account per sweep. An offline account, or one holding no token at all, is
     * answered without a request.
     *
     * @param account the account to check
     * @return a future holding what the account is worth right now
     * @since 0.8.0
     */
    CompletableFuture<AccountStatus> check(AltAccount account);

    /**
     * Makes an account usable if it can be, renewing from its refresh token when the stored one is
     * spent and persisting the rotated credential.
     *
     * <p>A live token on an account that cannot be renewed is reported valid without a request, so this
     * is not more expensive than a login for the common case. Never installs a session.
     *
     * @param account the account to refresh
     * @return a future holding the outcome, carrying renewed credentials when there are any
     * @since 0.8.0
     */
    CompletableFuture<AccountStatus> refresh(AltAccount account);
}
