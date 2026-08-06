package lol.trq.alts.bulk;

import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.model.AltAccount;

/**
 * What became of one entry in a bulk run.
 *
 * <p>The {@code label} is the resolved username, or a positional {@code "entry 12"} before one is known.
 * It is deliberately never the input line: for an import that line is a credential, and results are
 * exactly what a host renders into a list and writes to a log.
 *
 * @param index the entry's position in the list it was given, counting from 0
 * @param label a name safe to display and log
 * @param success whether the entry succeeded
 * @param account the resolved or refreshed account, or {@code null} when the entry failed
 * @param reason the classified cause, {@link FailureReason#NONE} on success
 * @param message a short human-readable description
 * @param attempts how many times this entry was tried, counting the first
 * @author trq
 * @since 0.9.0
 */
public record BulkEntryResult(
        int index,
        String label,
        boolean success,
        AltAccount account,
        FailureReason reason,
        String message,
        int attempts) {

    /**
     * Creates a successful result.
     *
     * @param index the entry's position
     * @param account the account it produced
     * @param message a short description
     * @param attempts how many times it was tried
     * @return a successful result labelled with the account's username
     * @since 0.9.0
     */
    public static BulkEntryResult success(int index, AltAccount account, String message, int attempts) {
        return new BulkEntryResult(index, account.username(), true, account, FailureReason.NONE, message, attempts);
    }

    /**
     * Creates a failed result.
     *
     * @param index the entry's position
     * @param label a name safe to display, never the input line
     * @param reason the classified cause
     * @param message a short description
     * @param attempts how many times it was tried
     * @return a failed result
     * @since 0.9.0
     */
    public static BulkEntryResult failure(int index, String label, FailureReason reason, String message, int attempts) {
        return new BulkEntryResult(index, label, false, null, reason, message, attempts);
    }

    /**
     * Returns the label an entry carries before it has resolved a name.
     *
     * @param index the entry's position
     * @return a positional label
     * @since 0.9.0
     */
    public static String positionalLabel(int index) {
        return "entry " + (index + 1);
    }
}
