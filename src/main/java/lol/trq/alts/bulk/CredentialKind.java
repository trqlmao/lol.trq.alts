package lol.trq.alts.bulk;

import java.util.Locale;

/**
 * What one line of a pasted credential list appears to be.
 *
 * <p>Public because a host wants to show a user what it thinks it is about to import before running it,
 * and because guessing wrong about a credential should be visible rather than silent.
 *
 * @author trq
 * @since 0.9.0
 */
public enum CredentialKind {

    /** A Microsoft OAuth refresh token, possibly prefixed with a name or a bearer marker. */
    REFRESH_TOKEN,

    /** A Minecraft services session token — a JWT. */
    SESSION_TOKEN,

    /** Exported browser cookies, in any of the shapes the cookie route reads. */
    COOKIE_TEXT,

    /** A bare username, for an offline account. */
    OFFLINE_NAME,

    /** Something this cannot place. Never guessed at. */
    UNKNOWN;

    /** Longest a Minecraft username can be, which is what makes a bare word recognisable as one. */
    private static final int MAX_USERNAME = 16;

    /**
     * Decides what a line is.
     *
     * <p>Checked most specific first, and anything left over is {@link #UNKNOWN} rather than assumed.
     * Feeding a mistyped line to the wrong route produces a failure about the credential being bad,
     * which sends the user looking in the wrong place.
     *
     * @param entry the line to classify
     * @return what it appears to be
     * @since 0.9.0
     */
    public static CredentialKind detect(String entry) {
        if (entry == null || entry.isBlank()) {
            return UNKNOWN;
        }
        String value = stripWrapping(entry.trim());

        if (value.startsWith("eyJ") || value.contains(":eyJ")) {
            return SESSION_TOKEN;
        }
        if (value.startsWith("M.") || value.contains(":M.")) {
            return REFRESH_TOKEN;
        }
        // Cookie exports are recognised by content rather than shape: they arrive as JSON, as
        // tab-separated Netscape rows, and as text that lost its structure in a copy-paste.
        if (value.contains("login.live.com") || value.contains("\t") || value.contains("\"name\"")) {
            return COOKIE_TEXT;
        }
        if (value.length() <= MAX_USERNAME && value.matches("\\w+")) {
            return OFFLINE_NAME;
        }
        return UNKNOWN;
    }

    /**
     * Removes surrounding quotes and a bearer prefix, the two things a pasted credential picks up on its
     * way out of a config file or an HTTP header.
     *
     * @param value the trimmed line
     * @return the line with its wrapping removed
     */
    private static String stripWrapping(String value) {
        String stripped = value;
        if (stripped.length() >= 2
                && ((stripped.startsWith("\"") && stripped.endsWith("\""))
                        || (stripped.startsWith("'") && stripped.endsWith("'")))) {
            stripped = stripped.substring(1, stripped.length() - 1).trim();
        }
        if (stripped.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            stripped = stripped.substring(7).trim();
        }
        return stripped;
    }
}
