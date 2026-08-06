package lol.trq.alts.account;

import java.io.Serial;

/**
 * Thrown when an account-services call fails in a way the caller should see rather than a {@code null}
 * papering over it — a refused token, a malformed request, an unreachable service.
 *
 * <p>Carries the HTTP status when there was one ({@code 0} otherwise), so a caller can branch on a 401
 * (re-auth) versus a 429 (back off) versus a transport failure without matching a message string.
 *
 * @author trq
 * @since 1.0.0
 */
public final class AccountException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;

    /**
     * Creates an exception carrying an HTTP status.
     *
     * @param message the failure description
     * @param status the HTTP status, or {@code 0} when the failure was not an HTTP response
     * @since 1.0.0
     */
    public AccountException(String message, int status) {
        super(message);
        this.status = status;
    }

    /**
     * Creates an exception wrapping an underlying failure.
     *
     * @param message the failure description
     * @param cause the underlying failure
     * @since 1.0.0
     */
    public AccountException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    /**
     * Returns the HTTP status this failure carried, or {@code 0} when it was not an HTTP response.
     *
     * @return the status
     * @since 1.0.0
     */
    public int status() {
        return status;
    }

    /**
     * Returns whether the token was refused (401 or 403), so the account needs re-authenticating.
     *
     * @return true if the status is 401 or 403
     * @since 1.0.0
     */
    public boolean tokenRefused() {
        return status == 401 || status == 403;
    }

    /**
     * Returns whether the service asked the caller to slow down (429).
     *
     * @return true if the status is 429
     * @since 1.0.0
     */
    public boolean rateLimited() {
        return status == 429;
    }
}
