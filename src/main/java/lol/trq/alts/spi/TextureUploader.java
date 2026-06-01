package lol.trq.alts.spi;

import java.io.InputStream;

/**
 * Uploads decoded image bytes into a host-managed GPU texture and returns an opaque host handle. The
 * handle type {@code H} is whatever the host renderer uses (for example a NanoVG image handle, or a
 * Skija texture reference) — the library treats it as opaque and never inspects it.
 *
 * <p>Always invoked on the main/render thread; the library marshals via {@link MainThreadExecutor}
 * before calling.
 *
 * @param <H> the host's opaque texture-handle type
 * @author trq
 * @since 0.1.0
 */
@FunctionalInterface
public interface TextureUploader<H> {

    /**
     * Uploads an image and returns its handle.
     *
     * @param imageBytes a stream over a complete encoded image (PNG)
     * @return the host texture handle, or {@code null} if the upload failed
     */
    H upload(InputStream imageBytes);
}
