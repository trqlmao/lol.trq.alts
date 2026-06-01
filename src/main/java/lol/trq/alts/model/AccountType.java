package lol.trq.alts.model;

/**
 * The authentication protocols a stored account can be created from.
 *
 * @author trq
 * @since 0.1.0
 */
public enum AccountType {
    /** Official Microsoft OAuth 2.0 authentication. */
    MICROSOFT,

    /** Authentication via browser session cookies. */
    COOKIE,

    /** Direct login using a pre-existing Mojang/Microsoft session token. */
    SESSION,

    /** Unauthenticated login for cracked/offline use. */
    OFFLINE
}
