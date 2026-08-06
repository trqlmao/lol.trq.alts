package lol.trq.alts.account;

/**
 * The Minecraft services base a host's account calls go to. Defaults to the public host; a host fronting
 * the services with its own proxy, or a test pointing at a loopback, overrides the base and every path
 * is built from it.
 *
 * @param base the Minecraft services origin, without a trailing slash
 * @author trq
 * @since 1.0.0
 */
public record AccountEndpoints(String base) {

    /** The public Minecraft services origin. */
    public static final String DEFAULT_BASE = "https://api.minecraftservices.com";

    /** Substitutes the default base for a blank one, and trims any trailing slash. */
    public AccountEndpoints {
        base = base == null || base.isBlank() ? DEFAULT_BASE : base;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
    }

    /** Returns the endpoints for the public Minecraft services. */
    public static AccountEndpoints defaults() {
        return new AccountEndpoints(DEFAULT_BASE);
    }

    String profile() {
        return base + "/minecraft/profile";
    }

    String entitlements() {
        return base + "/entitlements/mcstore";
    }

    String nameAvailability(String name) {
        return base + "/minecraft/profile/name/" + name + "/available";
    }

    String nameChange(String name) {
        return base + "/minecraft/profile/name/" + name;
    }

    String nameChangeInfo() {
        return base + "/minecraft/profile/namechange";
    }

    String skins() {
        return base + "/minecraft/profile/skins";
    }

    String activeSkin() {
        return base + "/minecraft/profile/skins/active";
    }

    String activeCape() {
        return base + "/minecraft/profile/capes/active";
    }
}
