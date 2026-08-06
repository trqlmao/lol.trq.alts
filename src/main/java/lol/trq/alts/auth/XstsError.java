package lol.trq.alts.auth;

/**
 * Why the XSTS step of Microsoft authentication refused, decoded from its {@code XErr} code.
 *
 * <p>The XSTS endpoint answers a refusal with a numeric {@code XErr} that says exactly what is wrong,
 * and the library used to read only whether a token came back — so every one of these surfaced as a bare
 * "Xbox auth failed", which tells a user nothing they can act on. Each of these is something the user
 * fixes in a specific place, so classifying it lets a host say where.
 *
 * @author trq
 * @since 1.0.0
 */
public enum XstsError {

    /**
     * The Microsoft account has never signed into Xbox, so it has no Xbox profile to authorize. The user
     * signs in once at xbox.com to create one.
     */
    NO_XBOX_ACCOUNT(2148916233L),

    /** The account is from a country where Xbox Live is not available. */
    REGION_BLOCKED(2148916235L),

    /** The account needs adult verification (South Korea). */
    ADULT_VERIFICATION_REQUIRED(2148916236L),

    /** The account needs adult verification (South Korea) — the second code the service uses for it. */
    ADULT_VERIFICATION_REQUIRED_ALT(2148916237L),

    /**
     * The account is a child under an adult's Microsoft Family, and must be added to a family before it
     * can sign into Xbox Live.
     */
    CHILD_ACCOUNT(2148916238L),

    /** An {@code XErr} this release does not recognise. */
    UNKNOWN(0L);

    private final long code;

    XstsError(long code) {
        this.code = code;
    }

    /**
     * Returns the raw {@code XErr} code this value maps.
     *
     * @return the code, or {@code 0} for {@link #UNKNOWN}
     * @since 1.0.0
     */
    public long code() {
        return code;
    }

    /**
     * Maps an {@code XErr} code onto a value.
     *
     * @param xerr the raw code
     * @return the matching error, {@link #UNKNOWN} for anything unrecognised
     * @since 1.0.0
     */
    public static XstsError fromCode(long xerr) {
        for (XstsError error : values()) {
            if (error != UNKNOWN && error.code == xerr) {
                return error;
            }
        }
        return UNKNOWN;
    }
}
