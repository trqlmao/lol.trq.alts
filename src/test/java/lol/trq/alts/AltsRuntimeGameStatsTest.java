package lol.trq.alts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lol.trq.alts.model.GameStats;
import lol.trq.alts.spi.StaticGameStatsSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Wiring tests for the multi-server game-stats cache on {@link AltsRuntime}. */
class AltsRuntimeGameStatsTest {

    private static AltsRuntime.Builder<Object> baseBuilder(Path dir) {
        return new AltsRuntime.Builder<Object>()
                .sessionInjector(session -> {})
                .vaultDirectory(() -> dir)
                .mainThread(Runnable::run)
                .toastSink((level, title, description, durationMs) -> {});
    }

    private static GameStats awaitStats(AltsRuntime<?> alts, String serverId, String uuid) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            GameStats stats = alts.gameStats(serverId).get(uuid);
            if (stats != null) {
                return stats;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("game stats never filled for " + serverId + "/" + uuid);
    }

    @Test
    void registeredServerYieldsItsChips(@TempDir Path dir) throws Exception {
        AltsRuntime<Object> alts = baseBuilder(dir)
                .gameStatsSource(new StaticGameStatsSource(
                        "example.net", Map.of("uuid-1", List.of(new GameStats.Stat("rank", "Veteran")))))
                .build();

        GameStats stats = awaitStats(alts, "example.net", "uuid-1");
        assertEquals("example.net", stats.serverId());
        assertEquals("Veteran", stats.stats().get(0).value());
    }

    @Test
    void unknownServerAlwaysNull(@TempDir Path dir) throws Exception {
        AltsRuntime<Object> alts = baseBuilder(dir).build();
        assertNull(alts.gameStats("nope").get("uuid-1"));
        Thread.sleep(100);
        assertNull(alts.gameStats("nope").get("uuid-1"), "an unregistered server never yields stats");
    }

    @Test
    void unknownUuidOnKnownServerStaysNull(@TempDir Path dir) throws Exception {
        AltsRuntime<Object> alts = baseBuilder(dir)
                .gameStatsSource(new StaticGameStatsSource("example.net", Map.of()))
                .build();
        assertNull(alts.gameStats("example.net").get("ghost"));
    }

    @Test
    void twoServersAreIndependent(@TempDir Path dir) throws Exception {
        AltsRuntime<Object> alts = baseBuilder(dir)
                .gameStatsSource(new StaticGameStatsSource("a", Map.of("u", List.of(new GameStats.Stat("k", "A")))))
                .gameStatsSource(new StaticGameStatsSource("b", Map.of("u", List.of(new GameStats.Stat("k", "B")))))
                .build();

        assertEquals("A", awaitStats(alts, "a", "u").stats().get(0).value());
        assertEquals("B", awaitStats(alts, "b", "u").stats().get(0).value());
    }

    @Test
    void duplicateServerIdThrows(@TempDir Path dir) {
        AltsRuntime.Builder<Object> builder =
                baseBuilder(dir).gameStatsSource(new StaticGameStatsSource("dup", Map.of()));
        assertThrows(
                IllegalStateException.class, () -> builder.gameStatsSource(new StaticGameStatsSource("dup", Map.of())));
    }
}
