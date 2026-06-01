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
     * An immutable representation of a login attempt's outcome.
     *
     * @param success whether the authentication was successful
     * @param message a descriptive message (success notification or error reason)
     * @param account the authenticated account object, or null if the attempt failed
     * @author trq
     * @since 0.1.0
     */
    record LoginResult(boolean success, String message, AltAccount account) {

        /**
         * Creates a successful login result.
         *
         * @param account the account that was successfully authenticated
         * @return a new result indicating success
         */
        public static LoginResult success(AltAccount account) {
            return new LoginResult(true, "Logged in successfully", account);
        }

        /**
         * Creates a failed login result.
         *
         * @param reason the reason why the authentication failed
         * @return a new result indicating failure
         */
        public static LoginResult failure(String reason) {
            return new LoginResult(false, reason, null);
        }
    }
}
