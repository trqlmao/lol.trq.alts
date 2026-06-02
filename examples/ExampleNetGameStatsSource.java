import java.util.List;
import lol.trq.alts.model.GameStats;
import lol.trq.alts.spi.GameStatsSource;

/**
 * Example {@link GameStatsSource} for a fictional {@code example.net} server. A real source would call
 * the server's API inside {@code fetch}; this one returns fixed chips so the example stays dependency
 * free. Wire it with {@code new AltsRuntime.Builder<H>()...gameStatsSource(new ExampleNetGameStatsSource())}
 * and read it back with {@code runtime.gameStats("example.net").get(uuid)}.
 *
 * <p>This file is compiled by the {@code examples} source set (see build.gradle) so it cannot drift from
 * the library API. It is illustrative and is not part of the published jar.
 */
public final class ExampleNetGameStatsSource implements GameStatsSource {

    @Override
    public String serverId() {
        return "example.net";
    }

    @Override
    public GameStats fetch(String uuid) {
        // A real implementation would look the player up on example.net here, then format whatever
        // stats matter for the card. The library never interprets these chips.
        List<GameStats.Stat> chips = List.of(
                new GameStats.Stat("rank", "Veteran"),
                new GameStats.Stat("wins", "128"),
                new GameStats.Stat("kdr", "1.74"));
        return new GameStats(serverId(), uuid, chips, System.currentTimeMillis());
    }
}
