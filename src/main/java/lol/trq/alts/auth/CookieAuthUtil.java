package lol.trq.alts.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import lol.trq.alts.net.HttpUtil;
import lol.trq.alts.net.NetworkScope;

/**
 * Authenticates Minecraft accounts using browser session cookies.
 *
 * <p>Supports both standard Netscape cookie file formats and "mangled" formats resulting from
 * inconsistent copy-pasting. Simulates the browser redirect chain required to convert Microsoft
 * session cookies into an XSTS token and finally a Minecraft access token.
 *
 * @author trq
 * @since 0.1.0
 */
public final class CookieAuthUtil {

    /** Standard User-Agent to mimic a modern Chrome browser during the OAuth flow. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36";

    /**
     * Known cookie names required for Microsoft/Xbox Live authentication. Ordered by length to ensure
     * precise matching during mangled parsing.
     */
    private static final String[] KNOWN_COOKIE_NAMES = {
        "MicrosoftApplicationsTelemetryDeviceId",
        "__Host-MSAAUTHP",
        "MSPRequ",
        "MSPPre",
        "MSPCID",
        "MSPVis",
        "MSPOK",
        "OParams",
        "SDIDC",
        "JSHP",
        "MSCC",
        "uaid"
    };

    private CookieAuthUtil() {}

    /** Where the sign-in starts: the Xbox Live SISU endpoint, asked to land on the Minecraft login page. */
    private static final String SISU_START =
            "https://sisu.xboxlive.com/connect/XboxLive/?state=login&cobrandId=8058f65d-ce06-4c30-9559-473c9275a65d&tid=896928775&ru=https://www.minecraft.net/en-us/login&aid=1142970254&as=0";

    /** The query or fragment parameter the last redirect carries the token bundle in. */
    private static final String ACCESS_TOKEN_PARAM = "accessToken=";

    /** The relying party whose XSTS token Minecraft services accept. */
    private static final String MINECRAFT_RELYING_PARTY = "rp://api.minecraftservices.com/";

    /**
     * Executes the full cookie-based authentication flow.
     *
     * <p>The chain is four redirects long. SISU hands off to the Microsoft identity platform, which
     * hands off to {@code login.live.com}; that is where the session cookies sign the user in, and it
     * answers with a code for SISU, which SISU then turns into the redirect that carries the token.
     * The cookies go only to the two Microsoft sign-in hosts: SISU needs none on the way in, and gets
     * none on the way out.
     *
     * @param cookieData the raw string containing cookie information
     * @return a {@link MinecraftProfile} containing the authenticated session data
     * @throws Exception if parsing fails or the redirect chain is interrupted
     */
    public static MinecraftProfile authenticate(String cookieData) throws Exception {
        String cookieHeader = parseCookies(cookieData);

        String identityPlatform = followRedirect(SISU_START, null);
        String liveLogin = followRedirect(identityPlatform, cookieHeader);
        String sisuCallback = followRedirect(liveLogin, cookieHeader);
        String landing = followRedirect(sisuCallback, null);

        XstsTokenData xstsData = extractXstsFromRedirect(landing);
        String mcToken = authenticateWithMinecraft(xstsData);

        return getMinecraftProfile(mcToken);
    }

    /** How deep a JSON export is walked before it is treated as hostile rather than merely nested. */
    private static final int MAX_JSON_DEPTH = 32;

    /**
     * High-level entry point for parsing cookie data from an ambiguous string. Three shapes are
     * recognised, in the order a real export is cheapest to identify: a JSON array or object, the
     * tab-separated Netscape format, and text that has been through a copy-paste and lost its structure.
     *
     * <p>Package-private so the shapes can be tested without a network round.
     *
     * @param cookieData the input string
     * @return a formatted HTTP Cookie header string
     */
    static String parseCookies(String cookieData) {
        String trimmed = cookieData.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            String json = parseJsonCookies(trimmed);
            if (!json.isEmpty()) return json;
        }
        if (cookieData.contains("\n") && cookieData.split("\n").length > 3) {
            String result = parseDelimitedCookies(cookieData);
            if (!result.isEmpty()) return result;
        }
        return parseMangledCookies(cookieData);
    }

    /**
     * Parses the JSON export the common cookie-editor extensions produce: an array of objects each
     * carrying at least a {@code name} and a {@code value}. Wrappers vary — some tools nest the array
     * under a key, some export one object per domain — so the tree is walked rather than assumed.
     *
     * <p>Without this, a JSON export fell through to the mangled parser, which found the cookie names it
     * knows and took the rest of the surrounding JSON as their values. That produced a Cookie header the
     * service rejected, and a failure that read as bad cookies rather than an unread file.
     *
     * @param data the trimmed JSON text
     * @return a formatted HTTP Cookie header string, or empty if the text held no cookie objects
     */
    private static String parseJsonCookies(String data) {
        StringBuilder builder = new StringBuilder();
        try {
            collectJsonCookies(JsonParser.parseString(data), builder, 0);
        } catch (RuntimeException notJson) {
            return "";
        }
        return builder.toString().trim();
    }

    /**
     * Walks a parsed export, appending every object that looks like a cookie.
     *
     * @param element the element to walk
     * @param out the header being built
     * @param depth the current nesting depth
     */
    private static void collectJsonCookies(JsonElement element, StringBuilder out, int depth) {
        if (element == null || depth > MAX_JSON_DEPTH) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectJsonCookies(child, out, depth + 1);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        String name = stringField(object, "name");
        String value = stringField(object, "value");
        if (name != null && value != null) {
            if (isValidCookie(name)) {
                out.append(name).append("=").append(value).append("; ");
            }
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            collectJsonCookies(entry.getValue(), out, depth + 1);
        }
    }

    /**
     * Reads a string member, treating a missing, null, or non-string value as absent.
     *
     * @param object the object to read from
     * @param key the member name
     * @return the string value, or null
     */
    private static String stringField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null
                        && element.isJsonPrimitive()
                        && element.getAsJsonPrimitive().isString()
                ? element.getAsString()
                : null;
    }

    /**
     * Parses cookies from a standard Netscape/tab-separated format.
     *
     * @param cookieData the tab-separated cookie string
     * @return a formatted HTTP Cookie header string
     */
    private static String parseDelimitedCookies(String cookieData) {
        StringBuilder cookieBuilder = new StringBuilder();
        String[] lines = cookieData.split("\\r?\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\\s+");
            if (parts.length >= 7) {
                String name = parts[parts.length - 2];
                String value = parts[parts.length - 1];
                if (isValidCookie(name)) {
                    cookieBuilder.append(name).append("=").append(value).append("; ");
                }
            }
        }
        return cookieBuilder.toString().trim();
    }

    /**
     * Robust parser for cookie data that has been smashed or poorly formatted. Splits data based on
     * the Microsoft login domain and extracts known keys.
     *
     * @param data the mangled input string
     * @return a formatted HTTP Cookie header string
     * @throws IllegalArgumentException if no valid cookies could be identified
     */
    private static String parseMangledCookies(String data) {
        StringBuilder result = new StringBuilder();
        String[] segments = data.split("(?=(?:\\.?)login\\.live\\.com)");

        for (String segment : segments) {
            if (segment.isBlank()) continue;

            for (String key : KNOWN_COOKIE_NAMES) {
                int keyIndex = segment.indexOf(key);
                if (keyIndex != -1) {
                    String rawValue = segment.substring(keyIndex + key.length()).trim();
                    if (!rawValue.isEmpty()) {
                        result.append(key).append("=").append(rawValue).append("; ");
                    }
                    break;
                }
            }
        }

        String finalCookies = result.toString().trim();
        if (finalCookies.isEmpty()) {
            throw new IllegalArgumentException("Could not parse valid cookies from data.");
        }

        return finalCookies;
    }

    /**
     * Filters out non-cookie metadata fields often found in cookie exports.
     *
     * @param name the potential cookie name
     * @return true if the name appears to be a valid cookie key
     */
    private static boolean isValidCookie(String name) {
        return !name.contains(".com")
                && !name.equals("/")
                && !name.equalsIgnoreCase("true")
                && !name.equalsIgnoreCase("false")
                && !name.matches("^[\\d.]+$");
    }

    /**
     * Performs a GET request to a URL and returns the {@code Location} header for redirection.
     *
     * <p>Goes through {@link HttpUtil} like every other request the library makes, which is what gives
     * this chain the finite timeouts, the refusal to follow redirects on its own, and the host's proxy.
     * Opening its own connection meant it had none of the three.
     *
     * @param urlString the URL to request
     * @param cookies the cookies to send with the request
     * @return the target redirection URL
     * @throws Exception if the server does not return a redirect or an error occurs
     */
    private static String followRedirect(String urlString, String cookies) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Encoding", "*");
        headers.put("Accept-Language", "en-US;q=0.8,en;q=0.7");
        // Only the Location header is wanted; the sign-in pages behind these URLs are large.
        headers.put("Range", "bytes=0-0");
        headers.put("User-Agent", USER_AGENT);
        if (cookies != null && !cookies.isEmpty()) {
            headers.put("Cookie", cookies);
        }

        // The services put raw spaces in the URLs they redirect to, which no URI parser accepts.
        HttpUtil.HeaderResponse response = HttpUtil.getHeader(
                urlString.replace(" ", "%20"), headers, "Location", NetworkScope.of(NetworkScope.Purpose.AUTH));

        if (response.value() == null) {
            if (response.status() >= 400) throw new Exception("HTTP " + response.status() + " during redirect");
            throw new Exception("No redirect location found");
        }
        return response.value();
    }

    /**
     * Extracts XSTS authentication data from the final redirect URI.
     *
     * <p>The token bundle is a Base64 JSON array of relying-party and token pairs, percent-encoded into
     * the URL. It is decoded and parsed rather than cut apart as text, so neither the encoding of its
     * padding nor the order of its fields decides whether a login works.
     *
     * <p>Package-private so the decoding can be tested without a network round.
     *
     * @param redirectUrl the URL containing the encoded access token
     * @return a record containing the XSTS token and user hash
     * @throws Exception if the token is missing or incorrectly encoded
     */
    static XstsTokenData extractXstsFromRedirect(String redirectUrl) throws Exception {
        if (redirectUrl == null || !redirectUrl.contains(ACCESS_TOKEN_PARAM)) {
            throw new Exception("Invalid redirect: No accessToken");
        }

        String raw = redirectUrl.substring(redirectUrl.indexOf(ACCESS_TOKEN_PARAM) + ACCESS_TOKEN_PARAM.length());
        int end = indexOfAny(raw, '&', '#');
        if (end != -1) raw = raw.substring(0, end);

        JsonArray bundle;
        try {
            // Not URLDecoder: that is for form bodies and would turn a Base64 '+' into a space.
            String encoded = percentDecode(raw);
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException notStandard) {
                bytes = Base64.getUrlDecoder().decode(encoded);
            }
            bundle = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonArray();
        } catch (RuntimeException malformed) {
            throw new Exception("Invalid redirect: accessToken could not be decoded");
        }

        try {
            for (JsonElement element : bundle) {
                JsonObject entry = element.getAsJsonObject();
                if (!MINECRAFT_RELYING_PARTY.equals(entry.get("Item1").getAsString())) continue;

                JsonObject item = entry.getAsJsonObject("Item2");
                String token = item.get("Token").getAsString();
                String uhs = item.getAsJsonObject("DisplayClaims")
                        .getAsJsonArray("xui")
                        .get(0)
                        .getAsJsonObject()
                        .get("uhs")
                        .getAsString();
                return new XstsTokenData(token, uhs);
            }
        } catch (RuntimeException malformed) {
            throw new Exception("Invalid redirect: accessToken is not a token bundle");
        }

        throw new Exception("Invalid redirect: No Minecraft token in accessToken");
    }

    /** {@return the first index of either character in {@code text}, or -1 if it holds neither} */
    private static int indexOfAny(String text, char first, char second) {
        int a = text.indexOf(first);
        int b = text.indexOf(second);
        if (a == -1) return b;
        if (b == -1) return a;
        return Math.min(a, b);
    }

    /**
     * Decodes {@code %XX} escapes and nothing else.
     *
     * @param text the percent-encoded text
     * @return the decoded text
     * @throws IllegalArgumentException if an escape is truncated or not hexadecimal
     */
    private static String percentDecode(String text) {
        if (text.indexOf('%') == -1) return text;

        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '%') {
                out.write(c);
                continue;
            }
            if (i + 2 >= text.length()) throw new IllegalArgumentException("truncated escape");
            out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
            i += 2;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Exchanges the XSTS token for a Minecraft services access token.
     *
     * @param xstsData the extracted XSTS data
     * @return the Minecraft services bearer token
     * @throws Exception if Minecraft services reject the identity token
     */
    private static String authenticateWithMinecraft(XstsTokenData xstsData) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + xstsData.uhs() + ";" + xstsData.token());
        body.addProperty("ensureLegacyEnabled", true);

        JsonObject response = HttpUtil.postJson(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                null,
                body.toString(),
                NetworkScope.of(NetworkScope.Purpose.AUTH));
        if (response == null || !response.has("access_token"))
            throw new Exception("Minecraft Services rejected the token");

        return response.get("access_token").getAsString();
    }

    /**
     * Fetches the Minecraft profile data (username and UUID) for the given token.
     *
     * @param mcToken the Minecraft services access token
     * @return the populated {@link MinecraftProfile}
     * @throws Exception if the profile could not be retrieved
     */
    private static MinecraftProfile getMinecraftProfile(String mcToken) throws Exception {
        JsonObject profile = HttpUtil.get(
                "https://api.minecraftservices.com/minecraft/profile",
                Map.of("Authorization", "Bearer " + mcToken),
                NetworkScope.of(NetworkScope.Purpose.PROFILE));
        if (profile == null) throw new Exception("Failed to fetch Minecraft profile");

        String uuid = profile.get("id").getAsString();
        String username = profile.get("name").getAsString();

        if (!uuid.contains("-")) {
            uuid = uuid.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5");
        }
        return new MinecraftProfile(username, uuid, mcToken, null, 0L);
    }

    /** Internal data structure for XSTS token information. */
    record XstsTokenData(String token, String uhs) {}
}
