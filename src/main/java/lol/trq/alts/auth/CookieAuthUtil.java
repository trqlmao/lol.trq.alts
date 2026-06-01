package lol.trq.alts.auth;

import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import lol.trq.alts.net.HttpUtil;

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

    /**
     * Executes the full cookie-based authentication flow.
     *
     * @param cookieData the raw string containing cookie information
     * @return a {@link MinecraftProfile} containing the authenticated session data
     * @throws Exception if parsing fails or the redirect chain is interrupted
     */
    public static MinecraftProfile authenticate(String cookieData) throws Exception {
        String cookieHeader = parseCookies(cookieData);

        // Follow the SISU (Single Sign-On) redirect chain
        String location1 = followRedirect(
                "https://sisu.xboxlive.com/connect/XboxLive/?state=login&cobrandId=8058f65d-ce06-4c30-9559-473c9275a65d&tid=896928775&ru=https://www.minecraft.net/en-us/login&aid=1142970254",
                "PHPSESSID=0");

        String location2 = followRedirect(location1.replace(" ", "%20"), cookieHeader);
        String location3 = followRedirect(location2, cookieHeader);

        // Exchange the final redirect URL for an XSTS token
        XstsTokenData xstsData = extractXstsFromRedirect(location3);
        String mcToken = authenticateWithMinecraft(xstsData);

        return getMinecraftProfile(mcToken);
    }

    /**
     * High-level entry point for parsing cookie data from an ambiguous string.
     *
     * @param cookieData the input string
     * @return a formatted HTTP Cookie header string
     */
    private static String parseCookies(String cookieData) {
        if (cookieData.contains("\n") && cookieData.split("\n").length > 3) {
            String result = parseDelimitedCookies(cookieData);
            if (!result.isEmpty()) return result;
        }
        return parseMangledCookies(cookieData);
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
     * @param urlString the URL to request
     * @param cookies the cookies to send with the request
     * @return the target redirection URL
     * @throws Exception if the server does not return a redirect or an error occurs
     */
    private static String followRedirect(String urlString, String cookies) throws Exception {
        URL url = new URI(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty(
                    "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "*");
            conn.setRequestProperty("Accept-Language", "en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Cookie", cookies);

            int status = conn.getResponseCode();
            String location = conn.getHeaderField("Location");

            if (location == null) {
                if (status >= 400) throw new Exception("HTTP " + status + " during redirect");
                throw new Exception("No redirect location found");
            }
            return location;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Extracts XSTS authentication data from the final redirect URI.
     *
     * @param redirectUrl the URL containing the encoded access token
     * @return a record containing the XSTS token and user hash
     * @throws Exception if the token is missing or incorrectly encoded
     */
    private static XstsTokenData extractXstsFromRedirect(String redirectUrl) throws Exception {
        if (!redirectUrl.contains("accessToken=")) throw new Exception("Invalid redirect: No accessToken");
        String accessToken = redirectUrl.split("accessToken=")[1];
        if (accessToken.contains("&")) accessToken = accessToken.split("&")[0];

        String decoded = new String(Base64.getDecoder().decode(accessToken), StandardCharsets.UTF_8);
        String tokenPart = decoded.split("\"rp://api.minecraftservices.com/\",")[1];
        String token = tokenPart.split("\"Token\":\"")[1].split("\"")[0];
        String uhs = tokenPart
                .split(Pattern.quote("{\"DisplayClaims\":{\"xui\":[{\"uhs\":\""))[1]
                .split("\"")[0];

        return new XstsTokenData(token, uhs);
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
                "https://api.minecraftservices.com/authentication/login_with_xbox", null, body.toString());
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
                "https://api.minecraftservices.com/minecraft/profile", Map.of("Authorization", "Bearer " + mcToken));
        if (profile == null) throw new Exception("Failed to fetch Minecraft profile");

        String uuid = profile.get("id").getAsString();
        String username = profile.get("name").getAsString();

        if (!uuid.contains("-")) {
            uuid = uuid.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5");
        }
        return new MinecraftProfile(username, uuid, mcToken);
    }

    /** Internal data structure for XSTS token information. */
    private record XstsTokenData(String token, String uhs) {}
}
