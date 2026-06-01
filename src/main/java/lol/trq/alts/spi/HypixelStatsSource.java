package lol.trq.alts.spi;

import lol.trq.alts.model.HypixelStats;

/**
 * Host hook that fetches an account's public Hypixel profile. The library never holds the Hypixel API
 * key or knows the endpoint — the host implements this (reads its key, calls {@code api.hypixel.net},
 * parses the JSON into a {@link HypixelStats}). Each host wires its own key and source.
 *
 * <p>Called off the main thread by an {@link lol.trq.alts.cache.AsyncCache}; may block on I/O. Return
 * {@code null} or throw to signal "no stats" — the cache marks the key failed either way.
 *
 * @author trq
 * @since 0.1.0
 */
@FunctionalInterface
public interface HypixelStatsSource {

    /**
     * Fetches stats for one player.
     *
     * @param uuid the dashed player UUID
     * @return the player's stats, or {@code null} if unavailable
     * @throws Exception if the lookup fails (treated as "no stats")
     */
    HypixelStats fetch(String uuid) throws Exception;
}
