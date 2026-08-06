package lol.trq.alts.account;

import com.google.gson.annotations.SerializedName;

/**
 * The arm model a skin is drawn with. Minecraft calls these {@code classic} (4px arms, "Steve") and
 * {@code slim} (3px arms, "Alex"); a skin uploaded with the wrong one renders with mismatched arms.
 *
 * @author trq
 * @since 1.0.0
 */
public enum SkinModel {

    /** The 4-pixel-arm model. The wire value is {@code CLASSIC} on a read and {@code classic} on a write. */
    @SerializedName("CLASSIC")
    CLASSIC("classic"),

    /** The 3-pixel-arm model. */
    @SerializedName("SLIM")
    SLIM("slim");

    private final String apiVariant;

    SkinModel(String apiVariant) {
        this.apiVariant = apiVariant;
    }

    /**
     * Returns the lowercase token the skin-change endpoint expects in its {@code variant} field.
     *
     * @return the API variant token
     * @since 1.0.0
     */
    public String apiVariant() {
        return apiVariant;
    }
}
