package lol.trq.alts.account;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * A player's full Minecraft profile as {@code GET /minecraft/profile} returns it — not just the name and
 * UUID an authentication flow needs, but the skins, capes, and any pending moderation actions on the
 * account.
 *
 * <p>Distinct from {@code MinecraftProfile}, which is the auth chain's internal result (identity plus
 * credentials). This is the read a host performs on a live account to show what it looks like and owns.
 *
 * @param uuid the account UUID (undashed, as the endpoint returns it)
 * @param name the current username
 * @param skins the skins on the account, at most one {@link Skin.State#ACTIVE}
 * @param capes the capes the account owns, at most one {@link Cape.State#ACTIVE}
 * @param profileActions pending moderation actions (empty for an account in good standing)
 * @author trq
 * @since 1.0.0
 */
public record PlayerProfile(
        @SerializedName("id") String uuid,
        @SerializedName("name") String name,
        @SerializedName("skins") List<Skin> skins,
        @SerializedName("capes") List<Cape> capes,
        @SerializedName("profileActions") List<String> profileActions) {

    /** Normalises the collections to non-null empties, so a caller never has to null-check them. */
    public PlayerProfile {
        skins = skins == null ? List.of() : List.copyOf(skins);
        capes = capes == null ? List.of() : List.copyOf(capes);
        profileActions = profileActions == null ? List.of() : List.copyOf(profileActions);
    }

    /**
     * Returns the active skin, if the account has one.
     *
     * @return the active skin, or {@code null} when none is active
     * @since 1.0.0
     */
    public Skin activeSkin() {
        return skins.stream()
                .filter(s -> s.state() == Skin.State.ACTIVE)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the active cape, if the account has one shown.
     *
     * @return the active cape, or {@code null} when none is active
     * @since 1.0.0
     */
    public Cape activeCape() {
        return capes.stream()
                .filter(c -> c.state() == Cape.State.ACTIVE)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns whether the account carries any pending moderation action, which a host may want to warn
     * about before relying on the account.
     *
     * @return true if a profile action is pending
     * @since 1.0.0
     */
    public boolean hasPendingActions() {
        return !profileActions.isEmpty();
    }
}
