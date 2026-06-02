package lol.trq.alts.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import lol.trq.alts.model.GameStats;

/**
 * A trivial {@link GameStatsSource} backed by a fixed, in-memory map of player UUID to display chips. It
 * is the simplest possible reference implementation and is handy for demos and tests where calling a
 * real server API is not wanted. Any UUID not in the map yields {@code null} ("no stats").
 *
 * @author trq
 * @since 0.3.0
 */
public final class StaticGameStatsSource implements GameStatsSource {

    private final String serverId;
    private final Map<String, List<GameStats.Stat>> chipsByUuid;

    /**
     * Creates a static source for one server.
     *
     * @param serverId the server id this source reports
     * @param chipsByUuid the chips to return per player UUID (copied defensively)
     */
    public StaticGameStatsSource(String serverId, Map<String, List<GameStats.Stat>> chipsByUuid) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.chipsByUuid = Map.copyOf(Objects.requireNonNull(chipsByUuid, "chipsByUuid"));
    }

    @Override
    public String serverId() {
        return serverId;
    }

    @Override
    public GameStats fetch(String uuid) {
        List<GameStats.Stat> chips = chipsByUuid.get(uuid);
        return chips == null ? null : new GameStats(serverId, uuid, chips, System.currentTimeMillis());
    }
}
