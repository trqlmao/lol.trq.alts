package lol.trq.alts.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameStatsTest {

    private final Gson gson = new GsonBuilder().create();

    @Test
    void roundTripsWithChips() {
        GameStats stats = new GameStats(
                "example.net",
                "uuid-1",
                List.of(new GameStats.Stat("rank", "Veteran"), new GameStats.Stat("wins", "128")),
                1000L);

        GameStats restored = gson.fromJson(gson.toJson(stats), GameStats.class);

        assertEquals(stats, restored);
        assertEquals(2, restored.stats().size());
        assertEquals("rank", restored.stats().get(0).label());
        assertEquals("Veteran", restored.stats().get(0).value());
    }

    @Test
    void roundTripsWithEmptyChips() {
        GameStats stats = new GameStats("example.net", "uuid-1", List.of(), 1000L);
        GameStats restored = gson.fromJson(gson.toJson(stats), GameStats.class);
        assertEquals(stats, restored);
        assertTrue(restored.stats().isEmpty());
    }

    @Test
    void parsesPayloadWithoutStatsArray() {
        GameStats restored = gson.fromJson("{\"serverId\":\"s\",\"uuid\":\"u\",\"fetchedAt\":5}", GameStats.class);
        assertEquals("s", restored.serverId());
        assertEquals(5L, restored.fetchedAt());
        assertNull(restored.stats(), "a missing stats array deserializes to null");
    }
}
