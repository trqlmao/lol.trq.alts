package lol.trq.alts.auth;

import java.time.Duration;
import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.model.AltAccount;

/**
 * What an account is worth right now, without having logged into it.
 *
 * <p>The {@code account} carried back is the record as it now stands — renewed credentials included — so
 * a caller updates its own view from one place rather than reassembling it.
 *
 * @param account the account this describes, possibly with freshly issued credentials
 * @param state the machine-readable outcome
 * @param reason the classified cause, {@link FailureReason#NONE} when the account is usable
 * @param message a short human-readable description
 * @param retryAfter how long the service asked the caller to wait before trying again, or {@code null}
 *     when it asked for nothing; added in 0.9.0
 * @author trq
 * @since 0.8.0
 */
public record AccountStatus(
        AltAccount account, State state, FailureReason reason, String message, Duration retryAfter) {

    /**
     * What an account can be.
     *
     * @author trq
     * @since 0.8.0
     */
    public enum State {
        /** The stored credentials work as they are. */
        VALID,

        /** The credentials were spent and have been renewed; the account carries the new ones. */
        RENEWED,

        /**
         * The stored token is not usable, and renewal has not been attempted. The account holds a
         * refresh token, so {@link AltAccountService#refresh(AltAccount)} is the next step rather than a
         * fresh interactive login. Only a read-only {@link AltAccountService#check(AltAccount)} reports
         * this.
         */
        EXPIRED,

        /**
         * The credentials are permanently spent and the account needs a fresh interactive login. Any
         * refresh token held for it has been discarded.
         */
        REAUTH_REQUIRED,

        /**
         * The credentials authenticated, but the account has no Minecraft profile. Neither retrying nor
         * re-authenticating helps.
         */
        NOT_ENTITLED,

        /** The service could not be reached, or failed. Retryable, and nothing was spent. */
        UNREACHABLE,

        /** Something else went wrong, which the library could not classify. */
        UNKNOWN
    }

    /**
     * Returns whether the account can be logged into as it stands.
     *
     * @return true if the state is {@link State#VALID} or {@link State#RENEWED}
     * @since 0.8.0
     */
    public boolean usable() {
        return state == State.VALID || state == State.RENEWED;
    }

    /**
     * Creates a status for an account whose stored credentials work.
     *
     * @param account the account
     * @return a valid status
     * @since 0.8.0
     */
    public static AccountStatus valid(AltAccount account) {
        return new AccountStatus(account, State.VALID, FailureReason.NONE, "Session is live", null);
    }

    /**
     * Creates a status for an account that was renewed.
     *
     * @param account the account carrying the freshly issued credentials
     * @return a renewed status
     * @since 0.8.0
     */
    public static AccountStatus renewed(AltAccount account) {
        return new AccountStatus(account, State.RENEWED, FailureReason.NONE, "Session renewed", null);
    }

    /**
     * Returns whether the service asked the caller to wait before trying this account again.
     *
     * @return true if a delay was stated
     * @since 0.9.0
     */
    public boolean rateLimited() {
        return retryAfter != null;
    }

    /**
     * Creates a status for an account that cannot be used as it stands.
     *
     * @param account the account this describes
     * @param state the outcome
     * @param reason the classified cause
     * @param message a short description
     * @return a failed status
     * @since 0.8.0
     */
    public static AccountStatus failure(AltAccount account, State state, FailureReason reason, String message) {
        return new AccountStatus(account, state, reason, message, null);
    }

    /**
     * Creates a status for an account the service asked the caller to come back to later.
     *
     * @param account the account this describes
     * @param state the outcome
     * @param reason the classified cause
     * @param message a short description
     * @param retryAfter how long the service asked the caller to wait, or {@code null}
     * @return a failed status carrying the requested delay
     * @since 0.9.0
     */
    public static AccountStatus failure(
            AltAccount account, State state, FailureReason reason, String message, Duration retryAfter) {
        return new AccountStatus(account, state, reason, message, retryAfter);
    }
}
