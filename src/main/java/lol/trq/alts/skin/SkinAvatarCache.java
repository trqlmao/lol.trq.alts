package lol.trq.alts.skin;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lol.trq.alts.spi.MainThreadExecutor;
import lol.trq.alts.spi.TextureUploader;

/**
 * In-memory cache of player face avatars sourced from mc-heads.net. Downloads happen on a background
 * thread; GPU uploads are marshalled onto the host's main/render thread via {@link MainThreadExecutor}
 * and produced by the host's {@link TextureUploader}.
 *
 * <p>The handle type {@code H} is the host renderer's opaque texture type, so this cache serves any
 * renderer (NanoVG, Skija, …) unchanged — only the eventual blit differs and stays host-side.
 *
 * <p>Entry states (a {@link ConcurrentHashMap} forbids nulls, so each is a real sentinel):
 *
 * <ul>
 *   <li>absent from map &rarr; not requested yet
 *   <li>{@link #PENDING} &rarr; fetch in flight
 *   <li>{@link #FAILED} &rarr; fetch or upload failed
 *   <li>a host handle &rarr; ready to draw
 * </ul>
 *
 * @param <H> the host's opaque texture-handle type
 * @author trq
 * @since 0.1.0
 */
public final class SkinAvatarCache<H> {

    private static final int FACE_PX = 64;
    private static final String AVATAR_URL = "https://mc-heads.net/avatar/%s/" + FACE_PX + ".png";

    /** Sentinel marking an in-flight fetch. */
    private static final Object PENDING = new Object();

    /** Sentinel marking a fetch or upload that failed. */
    private static final Object FAILED = new Object();

    private final Map<String, Object> cache = new ConcurrentHashMap<>();
    private final TextureUploader<H> uploader;
    private final MainThreadExecutor mainThread;

    /**
     * Creates a cache that uploads via {@code uploader} and marshals onto {@code mainThread}.
     *
     * @param uploader the host texture uploader; if null the cache stays empty and always returns null
     * @param mainThread the host main-thread executor used to marshal uploads
     */
    public SkinAvatarCache(TextureUploader<H> uploader, MainThreadExecutor mainThread) {
        this.uploader = uploader;
        this.mainThread = Objects.requireNonNull(mainThread, "MainThreadExecutor");
    }

    /**
     * Returns the cached avatar for {@code username} if ready, otherwise {@code null}. On a cache miss
     * this starts an async fetch so a later frame can pick it up.
     *
     * @param username the player name to resolve an avatar for; null or blank yields null
     * @return the ready handle, or null while a fetch is pending, has failed, or was just started
     */
    @SuppressWarnings("unchecked")
    public H get(String username) {
        if (username == null || username.isBlank() || uploader == null) {
            return null;
        }
        String key = username.toLowerCase();
        Object existing = cache.putIfAbsent(key, PENDING);
        if (existing == null) {
            fetchAsync(key);
            return null;
        }
        if (existing == PENDING || existing == FAILED) {
            return null;
        }
        return (H) existing;
    }

    private void fetchAsync(String key) {
        CompletableFuture.supplyAsync(() -> downloadBytes(key))
                .thenAccept(bytes -> mainThread.execute(() -> upload(key, bytes)));
    }

    private static byte[] downloadBytes(String key) {
        try {
            URL url = new URL(String.format(AVATAR_URL, key));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "lol.trq.alts/1.0");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(8_000);

            if (conn.getResponseCode() != 200) {
                return null;
            }

            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private void upload(String key, byte[] bytes) {
        if (bytes == null) {
            cache.put(key, FAILED);
            return;
        }
        try {
            H handle = uploader.upload(new ByteArrayInputStream(bytes));
            cache.put(key, handle != null ? handle : FAILED);
        } catch (Exception ignored) {
            cache.put(key, FAILED);
        }
    }
}
