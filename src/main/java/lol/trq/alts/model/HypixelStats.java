package lol.trq.alts.model;

import com.google.gson.annotations.SerializedName;

/**
 * A snapshot of an account's public Hypixel profile — rank, network level, and the BedWars / SkyWars
 * stats most useful for sizing up an alt. Produced host-side by a {@link lol.trq.alts.spi.HypixelStatsSource}
 * (which holds the API key and parses {@code api.hypixel.net}); the library only models and caches it.
 *
 * <p>{@code bedwars} / {@code skywars} are nullable — an account that never played a mode has none.
 *
 * @param uuid the player UUID these stats belong to
 * @param rank the display rank ("MVP+", "VIP", "DEFAULT", …)
 * @param networkLevel the Hypixel network level
 * @param bedwars the BedWars stats, or {@code null} if never played
 * @param skywars the SkyWars stats, or {@code null} if never played
 * @param fetchedAt the epoch-millis the source produced this snapshot
 * @author trq
 * @since 0.1.0
 */
public record HypixelStats(
        @SerializedName("uuid") String uuid,
        @SerializedName("rank") String rank,
        @SerializedName("networkLevel") int networkLevel,
        @SerializedName("bedwars") BedwarsStats bedwars,
        @SerializedName("skywars") SkywarsStats skywars,
        @SerializedName("fetchedAt") long fetchedAt) {

    /**
     * BedWars stats for a single account.
     *
     * @param star the BedWars star (prestige) level
     * @param finalKills lifetime final kills
     * @param finalDeaths lifetime final deaths
     * @param wins lifetime wins
     * @param fkdr final-kill / final-death ratio
     * @author trq
     * @since 0.1.0
     */
    public record BedwarsStats(
            @SerializedName("star") int star,
            @SerializedName("finalKills") int finalKills,
            @SerializedName("finalDeaths") int finalDeaths,
            @SerializedName("wins") int wins,
            @SerializedName("fkdr") double fkdr) {}

    /**
     * SkyWars stats for a single account.
     *
     * @param level the SkyWars level
     * @param kills lifetime kills
     * @param deaths lifetime deaths
     * @param wins lifetime wins
     * @param kdr kill / death ratio
     * @author trq
     * @since 0.1.0
     */
    public record SkywarsStats(
            @SerializedName("level") int level,
            @SerializedName("kills") int kills,
            @SerializedName("deaths") int deaths,
            @SerializedName("wins") int wins,
            @SerializedName("kdr") double kdr) {}
}
