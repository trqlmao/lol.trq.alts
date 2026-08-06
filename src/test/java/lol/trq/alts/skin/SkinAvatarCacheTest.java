package lol.trq.alts.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lol.trq.alts.spi.AvatarSource;
import org.junit.jupiter.api.Test;

/**
 * The avatar cache keys on UUID and treats a failure as temporary. Keying on username meant a rename
 * broke the head for good; caching a failure permanently meant one network blip did the same. Both are
 * regressions this guards.
 */
class SkinAvatarCacheTest {

    private static final byte[] PNG = {1, 2, 3, 4};

    /** Waits for the cache to fill for a key, since the fetch resolves on a background thread. */
    private static <H> H await(SkinAvatarCache<H> cache, String key) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            H handle = cache.get(key);
            if (handle != null) {
                return handle;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("avatar never filled for " + key);
    }

    @Test
    void fetchesThroughTheSourceAndUploads() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AvatarSource source = (uuid, size) -> {
            fetches.incrementAndGet();
            return PNG;
        };
        SkinAvatarCache<String> cache = new SkinAvatarCache<>(source, bytes -> "handle", Runnable::run);

        assertEquals("handle", await(cache, "uuid-1"));
        // A filled entry is served from memory, not refetched.
        cache.get("uuid-1");
        assertEquals(1, fetches.get(), "a ready avatar is not fetched twice");
    }

    @Test
    void nullUploaderMeansTheCacheStaysEmpty() {
        SkinAvatarCache<String> cache = new SkinAvatarCache<>((uuid, size) -> PNG, null, Runnable::run);

        assertNull(cache.get("uuid-1"), "with no uploader there is nowhere to put a handle");
    }

    /**
     * A failed fetch is retried once its backoff elapses, rather than caching the failure forever. The
     * old sentinel turned a single blip into a permanently broken head.
     */
    @Test
    void aFailureIsRetriedAfterItsBackoff() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AtomicLong now = new AtomicLong(0);
        // Fails the first call, succeeds the second.
        AvatarSource source = (uuid, size) -> fetches.incrementAndGet() == 1 ? null : PNG;
        SkinAvatarCache<String> cache = new SkinAvatarCache<>(source, bytes -> "handle", Runnable::run, now::get);

        // First get triggers the failing fetch.
        assertNull(cache.get("uuid-1"));
        Thread.sleep(30);
        assertNull(cache.get("uuid-1"), "still failed, and inside the backoff window");
        assertEquals(1, fetches.get(), "a failure inside its backoff is not retried");

        // Advance past the backoff; the next get re-arms the fetch, which now succeeds.
        now.addAndGet(10_000);
        assertEquals("handle", await(cache, "uuid-1"));
        assertTrue(fetches.get() >= 2, "the elapsed backoff must allow a retry");
    }

    @Test
    void keysAreCaseInsensitiveOnTheUuid() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AvatarSource source = (uuid, size) -> {
            fetches.incrementAndGet();
            return PNG;
        };
        SkinAvatarCache<String> cache = new SkinAvatarCache<>(source, bytes -> "handle", Runnable::run);

        assertNotNull(await(cache, "ABCDEF"));
        cache.get("abcdef");
        assertEquals(1, fetches.get(), "the same UUID in another case is the same entry");
    }

    @Test
    void defaultsToTheMojangSourceWhenNoneIsGiven() {
        // A null source must not NPE; it resolves to MojangAvatarSource. With no uploader the fetch never
        // runs, so this only proves construction and the null-source branch.
        SkinAvatarCache<String> cache = new SkinAvatarCache<>(null, null, Runnable::run);

        assertNull(cache.get("uuid-1"));
    }
}
