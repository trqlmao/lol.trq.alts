package lol.trq.alts.format;

import java.util.concurrent.TimeUnit;
import lol.trq.alts.model.AccountType;

/**
 * Pure-static text and colour formatting helpers for alt-manager UIs. Kept separate from any screen so
 * host UI code can reuse the same labels, relative-time strings, and deterministic fallback tints.
 *
 * @author trq
 * @since 0.1.0
 */
public final class AltManagerFormatting {

    private AltManagerFormatting() {}

    /**
     * Returns the human-readable label for an account type, used in row subtitles.
     *
     * @param type the account's authentication protocol
     * @return a short display label
     */
    public static String prettyType(final AccountType type) {
        return switch (type) {
            case MICROSOFT -> "Microsoft";
            case COOKIE -> "Cookie";
            case SESSION -> "Session";
            case OFFLINE -> "Offline";
        };
    }

    /**
     * Formats {@code epochMillis} as a short relative duration ("2h ago", "3w ago"), or the empty
     * string if the timestamp is zero or in the future. Truncates aggressively — minutes are the
     * smallest unit besides the "just now" bucket.
     *
     * @param epochMillis the reference time in epoch milliseconds
     * @return a short relative-time label, or the empty string if {@code epochMillis} is zero or lies
     *     in the future
     */
    public static String relativeTime(final long epochMillis) {
        if (epochMillis <= 0) return "";
        final long deltaMs = System.currentTimeMillis() - epochMillis;
        if (deltaMs < 0) return "";

        final long seconds = TimeUnit.MILLISECONDS.toSeconds(deltaMs);
        if (seconds < 60) return "just now";
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs);
        if (minutes < 60) return minutes + "m ago";
        final long hours = TimeUnit.MILLISECONDS.toHours(deltaMs);
        if (hours < 24) return hours + "h ago";
        final long days = TimeUnit.MILLISECONDS.toDays(deltaMs);
        if (days < 7) return days + "d ago";
        if (days < 30) return (days / 7) + "w ago";
        if (days < 365) return (days / 30) + "mo ago";
        return (days / 365) + "y ago";
    }

    /**
     * Deterministic HSV &rarr; ARGB conversion used to colour the avatar fallback tile when no skin
     * texture is available. Hue is taken from a hash of the username so the same name always gets the
     * same tint.
     *
     * @param hueDeg the hue in degrees; reduced modulo {@code 360}
     * @param s the saturation, in the range {@code 0.0} to {@code 1.0}
     * @param v the value (brightness), in the range {@code 0.0} to {@code 1.0}
     * @return the colour packed as a fully opaque {@code 0xAARRGGBB} integer
     */
    public static int hsvToRgb(final int hueDeg, final float s, final float v) {
        final float h = (hueDeg % 360) / 60f;
        final int i = (int) Math.floor(h);
        final float f = h - i;
        final float p = v * (1 - s);
        final float q = v * (1 - s * f);
        final float t = v * (1 - s * (1 - f));
        final float r, g, b;
        switch (i % 6) {
            case 0 -> {
                r = v;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = v;
                b = p;
            }
            case 2 -> {
                r = p;
                g = v;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = v;
            }
            case 4 -> {
                r = t;
                g = p;
                b = v;
            }
            default -> {
                r = v;
                g = p;
                b = q;
            }
        }
        return 0xff000000 | ((int) (r * 255) & 0xff) << 16 | ((int) (g * 255) & 0xff) << 8 | ((int) (b * 255) & 0xff);
    }
}
