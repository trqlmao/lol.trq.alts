package lol.trq.alts.account;

import com.google.gson.annotations.SerializedName;

/**
 * One cape the account owns, as the profile read returns it. An account may own several and show at most
 * one; {@link CapeService} switches between them by {@link #id()}.
 *
 * @param id the cape id, which {@link CapeService#setActive(String)} takes
 * @param state whether this cape is currently shown
 * @param url the cape texture URL
 * @param alias the cape's human name (for example {@code "Migrator"})
 * @author trq
 * @since 1.0.0
 */
public record Cape(
        @SerializedName("id") String id,
        @SerializedName("state") State state,
        @SerializedName("url") String url,
        @SerializedName("alias") String alias) {

    /**
     * Whether a cape is the one currently shown.
     *
     * @author trq
     * @since 1.0.0
     */
    public enum State {
        /** The cape currently shown. */
        @SerializedName("ACTIVE")
        ACTIVE,

        /** A cape the account owns but is not showing. */
        @SerializedName("INACTIVE")
        INACTIVE
    }
}
