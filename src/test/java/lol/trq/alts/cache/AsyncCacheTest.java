package lol.trq.alts.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncCacheTest {

    @Test
    void missReturnsNullThenFills() throws Exception {
        AsyncCache<String, String> cache = new AsyncCache<>(key -> "value:" + key);

        assertNull(cache.get("a"), "first get is a miss and must not block");
        assertEquals("value:a", awaitValue(cache, "a"));
    }

    @Test
    void nullLoaderMarksKeyFailed() throws Exception {
        AsyncCache<String, String> cache = new AsyncCache<>(key -> null);

        assertNull(cache.get("a"));
        Thread.sleep(100);
        assertNull(cache.get("a"), "a null fetch is terminal until invalidate()");
    }

    @Test
    void concurrentGetsTriggerOneFetchPerKey() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AsyncCache<String, String> cache = new AsyncCache<>(key -> {
            calls.incrementAndGet();
            sleepQuietly(50);
            return "v";
        });

        cache.get("a");
        cache.get("a");
        cache.get("a");

        assertEquals("v", awaitValue(cache, "a"));
        assertEquals(1, calls.get(), "PENDING dedup must collapse concurrent gets into one fetch");
    }

    @Test
    void invalidateForcesRefetch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AsyncCache<String, String> cache = new AsyncCache<>(key -> "v" + calls.incrementAndGet());

        assertEquals("v1", awaitValue(cache, "a"));
        cache.invalidate("a");
        assertEquals("v2", awaitValue(cache, "a"));
    }

    private static String awaitValue(AsyncCache<String, String> cache, String key) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            String value = cache.get(key);
            if (value != null) {
                return value;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("cache never filled key " + key);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
