package lol.trq.alts.account;

import java.util.Set;

/**
 * What an account owns, from {@code GET /entitlements/mcstore}.
 *
 * <p>Not a boolean. The endpoint returns a set of named products, and the distinctions in it are ones an
 * alt manager cares about: an account that owns Java outright is not the same as one entitled only
 * through a Game Pass that can lapse, and "owns Minecraft" hides that. The raw product ids are kept so a
 * host can read one this release has not named, and the helpers cover the common questions.
 *
 * @param products the product ids the account holds
 * @author trq
 * @since 1.0.0
 */
public record Entitlements(Set<String> products) {

    /** Product id for Minecraft: Java Edition owned outright. */
    public static final String PRODUCT_JAVA = "product_minecraft";

    /** Product id for Minecraft Bedrock. */
    public static final String PRODUCT_BEDROCK = "product_minecraft_bedrock";

    /** Product id for entitlement via Xbox Game Pass Ultimate. */
    public static final String PRODUCT_GAME_PASS_ULTIMATE = "product_game_pass_ultimate";

    /** Product id for entitlement via Xbox Game Pass (PC). */
    public static final String PRODUCT_GAME_PASS_PC = "product_game_pass_pc";

    /** Product id for Minecraft Legends. */
    public static final String PRODUCT_LEGENDS = "product_legends";

    /** Product id for Minecraft Dungeons. */
    public static final String PRODUCT_DUNGEONS = "product_dungeons";

    /** Copies the product set defensively. */
    public Entitlements {
        products = products == null ? Set.of() : Set.copyOf(products);
    }

    /**
     * Returns whether the account can play Java — whether it owns it outright or is entitled through a
     * Game Pass. This is the "can it log into a Java server" question.
     *
     * @return true if Java is playable
     * @since 1.0.0
     */
    public boolean ownsJava() {
        return products.contains(PRODUCT_JAVA) || viaGamePass();
    }

    /**
     * Returns whether the account owns Java outright, independent of any Game Pass.
     *
     * @return true if Java is owned directly
     * @since 1.0.0
     */
    public boolean ownsJavaOutright() {
        return products.contains(PRODUCT_JAVA);
    }

    /**
     * Returns whether the account owns Bedrock.
     *
     * @return true if Bedrock is owned
     * @since 1.0.0
     */
    public boolean ownsBedrock() {
        return products.contains(PRODUCT_BEDROCK);
    }

    /**
     * Returns whether the account's entitlement runs through a Game Pass, which can lapse — a materially
     * weaker claim than owning the game, and worth surfacing separately.
     *
     * @return true if entitled through a Game Pass tier
     * @since 1.0.0
     */
    public boolean viaGamePass() {
        return products.contains(PRODUCT_GAME_PASS_ULTIMATE) || products.contains(PRODUCT_GAME_PASS_PC);
    }

    /**
     * Returns whether the account holds no products at all — authenticated, but owns nothing.
     *
     * @return true if the product set is empty
     * @since 1.0.0
     */
    public boolean isEmpty() {
        return products.isEmpty();
    }
}
