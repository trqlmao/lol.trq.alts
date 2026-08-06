package lol.trq.alts.account;

import com.google.gson.annotations.SerializedName;

/**
 * One skin on an account, as the profile read returns it.
 *
 * @param id the skin's texture id
 * @param state whether this skin is currently worn
 * @param url the skin texture URL
 * @param variant the arm model this skin uses
 * @author trq
 * @since 1.0.0
 */
public record Skin(
        @SerializedName("id") String id,
        @SerializedName("state") State state,
        @SerializedName("url") String url,
        @SerializedName("variant") SkinModel variant) {

    /**
     * Whether a skin is the one currently shown.
     *
     * @author trq
     * @since 1.0.0
     */
    public enum State {
        /** The skin currently worn. */
        @SerializedName("ACTIVE")
        ACTIVE,

        /** A skin the account has but is not wearing. */
        @SerializedName("INACTIVE")
        INACTIVE
    }
}
