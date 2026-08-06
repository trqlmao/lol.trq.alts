package lol.trq.alts.account;

/**
 * Whether a username can be claimed, from {@code GET /minecraft/profile/name/{name}/available}.
 *
 * @author trq
 * @since 1.0.0
 */
public enum NameAvailability {

    /** The name is free to claim. */
    AVAILABLE,

    /** The name is taken. */
    DUPLICATE,

    /**
     * The name cannot be claimed — blocked, reserved, or otherwise disallowed. The endpoint does not say
     * which, so neither does this; a host reports it as "not allowed" rather than guessing a reason.
     */
    NOT_ALLOWED,

    /** The endpoint returned a status this release does not recognise. */
    UNKNOWN;

    /**
     * Maps the endpoint's {@code status} string onto a value.
     *
     * @param status the raw status, or null
     * @return the matching availability, {@link #UNKNOWN} for anything unrecognised
     * @since 1.0.0
     */
    public static NameAvailability from(String status) {
        if (status == null) {
            return UNKNOWN;
        }
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "AVAILABLE" -> AVAILABLE;
            case "DUPLICATE" -> DUPLICATE;
            case "NOT_ALLOWED" -> NOT_ALLOWED;
            default -> UNKNOWN;
        };
    }
}
