package lol.trq.alts.auth;

import com.google.gson.JsonObject;
import java.util.Map;
import lol.trq.alts.net.HttpUtil;

/**
 * Domain-specific network operations for Minecraft account profiles. Facilitates communication with
 * Mojang and Minecraft services for resolving player identities and validating session credentials.
 *
 * @author trq
 * @since 0.1.0
 */
public final class AccountNetworkUtil {

    private AccountNetworkUtil() {}

    /**
     * Resolves a Minecraft UUID to its corresponding username via the Mojang session server.
     *
     * @param uuid the player's UUID (supports both dashed and undashed formats)
     * @return the current username of the player, or {@code null} if the profile does not exist
     * @throws Exception if a network error occurs during the request
     */
    public static String lookupUsername(String uuid) throws Exception {
        // Mojang API expects undashed UUIDs
        String cleanUuid = uuid.replace("-", "");
        JsonObject response =
                HttpUtil.get("https://sessionserver.mojang.com/session/minecraft/profile/" + cleanUuid, null);

        return response != null && response.has("name") ? response.get("name").getAsString() : null;
    }

    /**
     * Validates a Minecraft access token and retrieves the associated profile details.
     *
     * @param token the Minecraft/Bearer access token to validate
     * @return a String array containing {@code [username, uuid]}, or {@code null} if the token is invalid
     * @throws Exception if a network error occurs during the request
     */
    public static String[] fetchProfileFromToken(String token) throws Exception {
        JsonObject response = HttpUtil.get(
                "https://api.minecraftservices.com/minecraft/profile", Map.of("Authorization", "Bearer " + token));

        if (response != null && response.has("name") && response.has("id")) {
            return new String[] {
                response.get("name").getAsString(), response.get("id").getAsString()
            };
        }

        return null;
    }
}
