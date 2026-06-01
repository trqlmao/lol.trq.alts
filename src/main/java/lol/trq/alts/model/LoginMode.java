package lol.trq.alts.model;

/**
 * Defines the behavior of a login action.
 *
 * @author trq
 * @since 0.1.0
 */
public enum LoginMode {
    /** Adds the account to the stored list and logs in. */
    ADD,

    /** Logs in without saving the account to the list. */
    DIRECT
}
