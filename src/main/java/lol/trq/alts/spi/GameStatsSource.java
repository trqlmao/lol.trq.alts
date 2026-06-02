package lol.trq.alts.spi;

import lol.trq.alts.model.GameStats;

/**
 * Host hook that fetches an account's public stats on one server. The library never holds an API key or
 * knows any server's endpoint or stat format: the host implements this (reads its key, calls the
 * server's API, formats the result into {@link GameStats} display chips). Register one source per server
 * on {@link lol.trq.alts.AltsRuntime.Builder}; the runtime caches per server, keyed by player UUID.
 *
 * <p>Game stats are an optional capability. A host may register no sources, and a registered source may
 * return {@code null} (or throw) to signal "no stats for this player". Called off the main thread by an
 * {@link lol.trq.alts.cache.AsyncCache}; may block on I/O.
 *
 * @author trq
 * @since 0.3.0
 */
public interface GameStatsSource {

    /**
     * Returns the identifier of the server this source covers. Used as the cache key and the
     * {@link GameStats#serverId()} stamp, and looked up by {@link lol.trq.alts.AltsRuntime#gameStats(String)}.
     *
     * @return the server id (stable, non-null)
     */
    String serverId();

    /**
     * Fetches stats for one player on this source's server.
     *
     * @param uuid the dashed player UUID
     * @return the player's stats, or {@code null} if unavailable
     * @throws Exception if the lookup fails (treated as "no stats")
     */
    GameStats fetch(String uuid) throws Exception;
}
