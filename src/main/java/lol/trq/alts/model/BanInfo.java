package lol.trq.alts.model;

import com.google.gson.annotations.SerializedName;

/**
 * A locally-observed ban record for an account. Hypixel exposes no ban-status API, so this is not a
 * lookup — it is set when the client <em>witnesses</em> a ban: the player gets banned while on this
 * account, or logs into an account already flagged. Persisted on the {@link AltAccount} so the row
 * can show a badge, and shared into a repository so members see which alts are burned.
 *
 * @param banned whether the account is currently considered banned
 * @param observedAt the epoch-millis the ban was observed
 * @param source how it was observed — e.g. {@code "self"} (banned while playing) or {@code "login"}
 * @param detail the ban message or reason, if one was captured
 * @param observedBy the member id (Ed25519 identity) that witnessed the ban in a shared repo, or
 *     {@code null} when observed locally / unattributed
 * @author trq
 * @since 0.1.0
 */
public record BanInfo(
        @SerializedName("banned") boolean banned,
        @SerializedName("observedAt") long observedAt,
        @SerializedName("source") String source,
        @SerializedName("detail") String detail,
        @SerializedName("observedBy") String observedBy) {

    /**
     * Creates a ban record stamped at the current time, unattributed.
     *
     * @param source how the ban was observed
     * @param detail the ban message or reason, or {@code null}
     * @return a {@code banned} record stamped now
     */
    public static BanInfo observed(String source, String detail) {
        return new BanInfo(true, System.currentTimeMillis(), source, detail, null);
    }

    /**
     * Creates a ban record stamped at the current time, attributed to the observing member.
     *
     * @param source how the ban was observed
     * @param detail the ban message or reason, or {@code null}
     * @param byMember the member id (Ed25519 identity) that witnessed the ban, or {@code null}
     * @return a {@code banned} record stamped now, attributed to {@code byMember}
     */
    public static BanInfo observed(String source, String detail, String byMember) {
        return new BanInfo(true, System.currentTimeMillis(), source, detail, byMember);
    }
}
