package lol.trq.alts.auth;

import lol.trq.alts.model.AltAccount;

/**
 * A functional callback for the outcome of asynchronous login operations, letting the client react to
 * successful authentications or display error messages without blocking the render thread.
 *
 * @author trq
 * @since 0.1.0
 */
@FunctionalInterface
public interface AltLoginCallback {

    /**
     * Invoked when a login operation has completed, regardless of whether it succeeded.
     *
     * @param result the result object containing the status, message, and account data
     */
    void onResult(LoginResult result);

    /**
     * Why a login attempt failed, in a form a host can branch on. Matching on the human-readable
     * message is not viable: it is subject to obfuscation and localization.
     *
     * @author trq
     * @since 0.6.0
     */
    enum FailureReason {
        /** The attempt succeeded; carried by every successful result. */
        NONE,

        /** The attempt failed for a reason the library cannot classify. */
        UNKNOWN,

        /**
         * The stored credentials are permanently spent and the account needs a fresh interactive
         * login. Any refresh token held for the account has been discarded.
         */
        REAUTH_REQUIRED,

        /** The supplied token was empty, malformed, or rejected as invalid. */
        INVALID_TOKEN,

        /** The attempt could not reach the authentication service, or the service failed. Retryable. */
        NETWORK,

        /** The flow requires host configuration that was not supplied. */
        NOT_CONFIGURED
    }

    /**
     * An immutable representation of a login attempt's outcome.
     *
     * @param success whether the authentication was successful
     * @param message a descriptive message (success notification or error reason)
     * @param account the authenticated account object, or null if the attempt failed
     * @param reason the machine-readable cause, {@link FailureReason#NONE} on success; added in 0.6.0
     * @author trq
     * @since 0.1.0
     */
    record LoginResult(boolean success, String message, AltAccount account, FailureReason reason) {

        /**
         * Creates a successful login result.
         *
         * @param account the account that was successfully authenticated
         * @return a new result indicating success
         */
        public static LoginResult success(AltAccount account) {
            return new LoginResult(true, "Logged in successfully", account, FailureReason.NONE);
        }

        /**
         * Creates a failed login result with no classified cause.
         *
         * @param reason the reason why the authentication failed
         * @return a new result indicating an unclassified failure
         */
        public static LoginResult failure(String reason) {
            return failure(reason, FailureReason.UNKNOWN);
        }

        /**
         * Creates a failed login result carrying a machine-readable cause.
         *
         * @param message the human-readable reason why the authentication failed
         * @param cause the classified cause, for hosts that branch on the outcome
         * @return a new result indicating failure
         * @since 0.6.0
         */
        public static LoginResult failure(String message, FailureReason cause) {
            return new LoginResult(false, message, null, cause);
        }
    }
}
