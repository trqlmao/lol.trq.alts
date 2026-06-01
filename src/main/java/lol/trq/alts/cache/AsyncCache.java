package lol.trq.alts.cache;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A lazy, non-blocking, per-key value cache. {@link #get(Object)} never blocks: on a cache miss it
 * returns {@code null} and fires a background fetch, so a later call (e.g. the next frame) sees the
 * filled value. This is the data-only distillation of {@link lol.trq.alts.skin.SkinAvatarCache} — it
 * drops the GPU/main-thread upload step, since plain data needs no marshalling.
 *
 * <p>Each key sits in one of three states, encoded as sentinels because a {@link ConcurrentHashMap}
 * forbids null values:
 *
 * <ul>
 *   <li>absent &rarr; never requested
 *   <li>{@link #PENDING} &rarr; a fetch is in flight
 *   <li>{@link #FAILED} &rarr; the last fetch threw or returned {@code null} (terminal until
 *       {@link #invalidate(Object)})
 *   <li>a {@code Hit} &rarr; a value with the timestamp it was fetched at
 * </ul>
 *
 * <p>With a positive {@code ttlMillis} the cache is <em>stale-while-revalidate</em>: once an entry
 * ages past the TTL, {@link #get(Object)} keeps returning the stale value <em>and</em> kicks off a
 * refresh in the background, so the UI never flickers to empty.
 *
 * <p>The {@code loader} runs on the common {@link java.util.concurrent.ForkJoinPool} via
 * {@link CompletableFuture#supplyAsync}; it must be thread-safe and should return {@code null} (or
 * throw) to signal failure.
 *
 * @param <K> the lookup key (e.g. a player UUID string)
 * @param <V> the cached value (e.g. a stats record)
 * @author trq
 * @since 0.1.0
 */
public final class AsyncCache<K, V> {

    /** Sentinel marking an in-flight fetch. */
    private static final Object PENDING = new Object();

    /** Sentinel marking a fetch that threw or returned {@code null}. */
    private static final Object FAILED = new Object();

    private record Hit(Object value, long fetchedAt) {}

    private final ConcurrentHashMap<K, Object> entries = new ConcurrentHashMap<>();
    private final Function<K, V> loader;
    private final long ttlMillis;

    /**
     * Creates a cache whose entries never expire (refresh only via {@link #invalidate(Object)}).
     *
     * @param loader the background fetch; returns {@code null} or throws to mark a key failed
     */
    public AsyncCache(Function<K, V> loader) {
        this(loader, 0L);
    }

    /**
     * Creates a cache with stale-while-revalidate expiry.
     *
     * @param loader the background fetch; returns {@code null} or throws to mark a key failed
     * @param ttlMillis how long a fetched value is fresh, in milliseconds; {@code <= 0} never expires
     */
    public AsyncCache(Function<K, V> loader, long ttlMillis) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ttlMillis = ttlMillis;
    }

    /**
     * Returns the cached value for {@code key} if present, otherwise {@code null}. On a miss this
     * starts a background fetch; on a stale hit it serves the stale value and refreshes in the
     * background.
     *
     * @param key the lookup key
     * @return the value if ready (possibly stale), or {@code null} while pending, failed, or just
     *     requested
     */
    @SuppressWarnings("unchecked")
    public V get(K key) {
        Object existing = entries.get(key);

        if (existing instanceof Hit hit) {
            if (ttlMillis > 0 && System.currentTimeMillis() - hit.fetchedAt() > ttlMillis) {
                // Stale: refresh in the background but keep serving the old value this call.
                if (entries.replace(key, hit, PENDING)) {
                    fetch(key);
                }
            }
            return (V) hit.value();
        }

        if (existing == null) {
            if (entries.putIfAbsent(key, PENDING) == null) {
                fetch(key);
            }
            return null;
        }

        // PENDING or FAILED
        return null;
    }

    private void fetch(K key) {
        CompletableFuture.supplyAsync(() -> loader.apply(key)).whenComplete((value, error) -> {
            if (error != null || value == null) {
                entries.put(key, FAILED);
            } else {
                entries.put(key, new Hit(value, System.currentTimeMillis()));
            }
        });
    }

    /**
     * Drops the entry for {@code key} so the next {@link #get(Object)} refetches it. Use to force a
     * refresh or to retry a {@link #FAILED} key.
     *
     * @param key the key to evict
     */
    public void invalidate(K key) {
        entries.remove(key);
    }

    /** Drops every entry. */
    public void clear() {
        entries.clear();
    }
}
