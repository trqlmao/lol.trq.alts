package lol.trq.alts.spi;

/**
 * Fetches a player's face as PNG bytes, given a UUID.
 *
 * <p>Optional, and it has a default. Without one wired, the library resolves the skin from Mojang's own
 * session server and crops the face itself ({@code MojangAvatarSource}), which keeps every alt's
 * identity between the host and Mojang — the two parties already talking during login — rather than
 * disclosing it to a third-party head-render service on every cache miss.
 *
 * <p>A host that wants a head-render service back, for its 3D renders or its CDN, installs
 * {@code UrlTemplateAvatarSource} (or its own implementation). That is a deliberate choice a host makes,
 * not the default it gets by doing nothing.
 *
 * @author trq
 * @since 0.10.0
 */
@FunctionalInterface
public interface AvatarSource {

    /**
     * Returns the face for {@code uuid} as PNG bytes at roughly {@code sizePx} square.
     *
     * <p>The size is a hint. A source is free to return the closest it has and let the host scale; the
     * cache does not require an exact match.
     *
     * @param uuid the dashed or undashed player UUID
     * @param sizePx the desired face size in pixels
     * @return the PNG bytes, or {@code null} when the face could not be produced
     * @throws Exception if the fetch failed in a way worth retrying
     * @since 0.10.0
     */
    byte[] avatarPng(String uuid, int sizePx) throws Exception;
}
