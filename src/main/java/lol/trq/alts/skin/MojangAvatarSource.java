package lol.trq.alts.skin;

import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import lol.trq.alts.net.HttpUtil;
import lol.trq.alts.net.NetworkScope;
import lol.trq.alts.spi.AvatarSource;

/**
 * The default {@link AvatarSource}: resolve the skin from Mojang's session server and crop the face
 * locally, so a player's identity is disclosed to no party the host was not already talking to.
 *
 * <p>The alternative a library reaches for — a third-party head-render URL keyed by name or UUID —
 * hands that service the identity of every account on every cache miss. For an alt manager that is not
 * one name; it is the whole list one person controls, arriving from one address. Mojang already knows
 * these accounts, since the host authenticated them through it, so sourcing the skin here removes a
 * trust relationship rather than adding one.
 *
 * <p>The face is the 8×8 head region with the hat overlay composited on top, matching what a head-render
 * service would return. A skin that cannot be read, or a profile with none, yields {@code null} and the
 * cache falls back to whatever the host draws for a missing head.
 *
 * @author trq
 * @since 0.10.0
 */
public final class MojangAvatarSource implements AvatarSource {

    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String TEXTURES_PROPERTY = "textures";

    /** The head occupies an 8×8 block at (8,8) on a 64-wide skin, and the hat an 8×8 block at (40,8). */
    private static final int FACE_X = 8;

    private static final int FACE_Y = 8;
    private static final int HAT_X = 40;
    private static final int FACE_SIZE = 8;

    /** Cap on a downloaded skin PNG. A vanilla skin is a couple of kilobytes; this is far past any real one. */
    private static final long MAX_SKIN_BYTES = 1L << 20;

    @Override
    public byte[] avatarPng(String uuid, int sizePx) throws Exception {
        String skinUrl = skinUrl(uuid);
        if (skinUrl == null) {
            return null;
        }
        byte[] skinBytes = HttpUtil.getBytes(
                skinUrl, null, MAX_SKIN_BYTES, NetworkScope.forAccount(NetworkScope.Purpose.AVATAR, uuid, null));
        if (skinBytes == null) {
            return null;
        }
        BufferedImage face = cropFace(skinBytes, Math.max(sizePx, FACE_SIZE));
        return face == null ? null : encode(face);
    }

    /**
     * Reads the profile's {@code textures} property and pulls the skin URL out of it.
     *
     * @param uuid the player UUID
     * @return the skin PNG URL, or {@code null} when the profile has no skin
     * @throws Exception if the profile lookup fails
     */
    private static String skinUrl(String uuid) throws Exception {
        String cleanUuid = uuid.replace("-", "");
        JsonObject profile = HttpUtil.get(
                PROFILE_URL + cleanUuid, null, NetworkScope.forAccount(NetworkScope.Purpose.AVATAR, uuid, null));
        if (profile == null || !profile.has("properties")) {
            return null;
        }
        for (var element : profile.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if (!TEXTURES_PROPERTY.equals(property.get("name").getAsString())) {
                continue;
            }
            String decoded =
                    new String(Base64.getDecoder().decode(property.get("value").getAsString()), StandardCharsets.UTF_8);
            JsonObject textures = com.google.gson.JsonParser.parseString(decoded)
                    .getAsJsonObject()
                    .getAsJsonObject("textures");
            if (textures == null || !textures.has("SKIN")) {
                return null;
            }
            return textures.getAsJsonObject("SKIN").get("url").getAsString();
        }
        return null;
    }

    /**
     * Crops the face out of a skin, composites the hat over it, and scales to the requested size.
     *
     * @param skinBytes the raw skin PNG
     * @param sizePx the target face size
     * @return the face image, or {@code null} when the skin could not be decoded
     */
    private static BufferedImage cropFace(byte[] skinBytes, int sizePx) {
        BufferedImage skin;
        try {
            skin = ImageIO.read(new ByteArrayInputStream(skinBytes));
        } catch (Exception unreadable) {
            return null;
        }
        if (skin == null || skin.getWidth() < HAT_X + FACE_SIZE || skin.getHeight() < FACE_Y + FACE_SIZE) {
            return null;
        }

        BufferedImage base = skin.getSubimage(FACE_X, FACE_Y, FACE_SIZE, FACE_SIZE);
        BufferedImage composed = new BufferedImage(FACE_SIZE, FACE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = composed.createGraphics();
        try {
            g.drawImage(base, 0, 0, null);
            // The hat layer is transparent where the player has no hat, so it composites to a no-op there
            // and to the overlay where they do.
            g.drawImage(skin.getSubimage(HAT_X, FACE_Y, FACE_SIZE, FACE_SIZE), 0, 0, null);
        } finally {
            g.dispose();
        }

        if (sizePx == FACE_SIZE) {
            return composed;
        }
        BufferedImage scaled = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D scaleG = scaled.createGraphics();
        try {
            // Nearest-neighbour: a face is pixel art, and smoothing it turns crisp blocks to mush.
            scaleG.setRenderingHint(
                    java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            scaleG.drawImage(composed, 0, 0, sizePx, sizePx, null);
        } finally {
            scaleG.dispose();
        }
        return scaled;
    }

    private static byte[] encode(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
