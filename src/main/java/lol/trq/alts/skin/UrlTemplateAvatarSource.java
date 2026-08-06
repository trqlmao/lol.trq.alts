package lol.trq.alts.skin;

import java.util.Objects;
import lol.trq.alts.net.HttpUtil;
import lol.trq.alts.net.NetworkScope;
import lol.trq.alts.spi.AvatarSource;

/**
 * An {@link AvatarSource} that fetches a face from a head-render service by filling a URL template.
 *
 * <p>This is the opt-in for a host that wants a third-party service back — its 3D head renders, its
 * CDN — after the library defaulted to sourcing faces from Mojang directly. Choosing it discloses each
 * account's UUID to that service on every cache miss, which is the cost the default exists to avoid; a
 * host makes that trade knowingly by installing this.
 *
 * <p>The template uses {@code {uuid}} and {@code {size}} placeholders, for example
 * {@code https://example.net/avatar/{uuid}/{size}.png}. The UUID is passed as given (dashed or not); a
 * host whose service wants one form normalises in the template it builds.
 *
 * @author trq
 * @since 0.10.0
 */
public final class UrlTemplateAvatarSource implements AvatarSource {

    /** Cap on a fetched avatar. Far past any real face PNG, and a guard on a service the library does not control. */
    private static final long MAX_AVATAR_BYTES = 1L << 20;

    private final String template;

    /**
     * Creates a source that fills the given template.
     *
     * @param template a URL with {@code {uuid}} and optionally {@code {size}} placeholders
     * @since 0.10.0
     */
    public UrlTemplateAvatarSource(String template) {
        this.template = Objects.requireNonNull(template, "template");
        if (!template.contains("{uuid}")) {
            throw new IllegalArgumentException("avatar template must contain a {uuid} placeholder");
        }
    }

    @Override
    public byte[] avatarPng(String uuid, int sizePx) throws Exception {
        String url = template.replace("{uuid}", uuid).replace("{size}", Integer.toString(sizePx));
        return HttpUtil.getBytes(
                url, null, MAX_AVATAR_BYTES, NetworkScope.forAccount(NetworkScope.Purpose.AVATAR, uuid, null));
    }
}
