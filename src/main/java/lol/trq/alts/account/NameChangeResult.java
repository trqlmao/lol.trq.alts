package lol.trq.alts.account;

import java.time.Duration;

/**
 * What a name change came to.
 *
 * <p>The mutation's status carries the meaning, and the reference tooling threw it away: a 400 is a
 * malformed or invalid name, a 403 is the name being unavailable or the account on cooldown, a 429
 * carries a wait, and a 404 means the account owns no Java to rename. This keeps them apart, and reads
 * the service's own error message where there is one, so a host can tell the user which.
 *
 * @param outcome the classified result
 * @param profile the updated profile on success, or {@code null}
 * @param message a short human-readable description
 * @param retryAfter how long the service asked the caller to wait, or {@code null}
 * @author trq
 * @since 1.0.0
 */
public record NameChangeResult(Outcome outcome, PlayerProfile profile, String message, Duration retryAfter) {

    /**
     * How a name change ended.
     *
     * @author trq
     * @since 1.0.0
     */
    public enum Outcome {
        /** The name was changed; {@link NameChangeResult#profile()} carries the updated profile. */
        CHANGED,

        /** The name is malformed or invalid (too long, illegal characters). */
        INVALID_NAME,

        /** The name is unavailable, or the account is within its name-change cooldown. */
        UNAVAILABLE,

        /** The account owns no Java edition to rename. */
        NOT_ENTITLED,

        /** The token was refused. */
        UNAUTHORIZED,

        /** The service asked the caller to slow down; {@link NameChangeResult#retryAfter()} may say how long. */
        RATE_LIMITED,

        /** The service could not be reached or failed for another reason. */
        FAILED
    }

    /**
     * Returns whether the name was changed.
     *
     * @return true on success
     * @since 1.0.0
     */
    public boolean success() {
        return outcome == Outcome.CHANGED;
    }
}
