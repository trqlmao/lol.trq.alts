package lol.trq.alts.auth;

import com.google.gson.JsonObject;
import java.util.Map;
import lol.trq.alts.net.HttpUtil;
import lol.trq.alts.net.NetworkScope;

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
     * The outcome of a profile lookup, carrying the status so a caller can tell a refused token from an
     * account with no profile from a service having a bad minute.
     *
     * @param status the HTTP status the profile endpoint answered with, or {@code 0} when it never
     *     answered
     * @param username the resolved username, or {@code null} when the lookup did not resolve one
     * @param uuid the resolved UUID, or {@code null} when the lookup did not resolve one
     * @param retryAfter how long the service asked the caller to wait, or {@code null} when it did not;
     *     added in 0.9.0
     * @author trq
     * @since 0.8.0
     */
    public record ProfileLookup(int status, String username, String uuid, java.time.Duration retryAfter) {

        /**
         * Returns whether the service is asking the caller to slow down.
         *
         * @return true if the status is 429
         * @since 0.9.0
         */
        public boolean rateLimited() {
            return status == 429;
        }

        /**
         * Returns whether the lookup resolved an identity.
         *
         * @return true if a username and UUID came back
         * @since 0.8.0
         */
        public boolean found() {
            return username != null && uuid != null;
        }

        /**
         * Returns whether the token was refused.
         *
         * @return true if the service answered 401 or 403
         * @since 0.8.0
         */
        public boolean refused() {
            return status == 401 || status == 403;
        }

        /**
         * Returns whether the credentials authenticated but no Minecraft profile exists for them.
         *
         * @return true if the service answered 404
         * @since 0.8.0
         */
        public boolean missingProfile() {
            return status == 404;
        }
    }

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
        JsonObject response = HttpUtil.get(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + cleanUuid,
                null,
                NetworkScope.forAccount(NetworkScope.Purpose.PROFILE, uuid, null));

        return response != null && response.has("name") ? response.get("name").getAsString() : null;
    }

    /**
     * Validates a Minecraft access token against the default profile endpoint.
     *
     * @param token the Minecraft/Bearer access token to validate
     * @return a String array containing {@code [username, uuid]}, or {@code null} if the token is invalid
     * @throws Exception if a network error occurs during the request
     * @deprecated since 0.8.0, use {@link #fetchProfile(String, String, NetworkScope)}. This form
     *     collapses a refused token and an account with no Minecraft profile onto the same {@code null},
     *     which are different problems with different answers.
     */
    @Deprecated(since = "0.8.0")
    public static String[] fetchProfileFromToken(String token) throws Exception {
        return fetchProfileFromToken(token, MicrosoftAuthConfig.DEFAULT_MINECRAFT_PROFILE_URL);
    }

    /**
     * Validates a Minecraft access token against a caller-supplied profile endpoint, so a host that
     * fronts Minecraft services with its own proxy validates through the same route it authenticates
     * through.
     *
     * @param token the Minecraft/Bearer access token to validate
     * @param profileUrl the Minecraft services profile endpoint
     * @return a String array containing {@code [username, uuid]}, or {@code null} if the token is invalid
     * @throws Exception if a network error occurs during the request
     * @since 0.6.0
     * @deprecated since 0.8.0, use {@link #fetchProfile(String, String, NetworkScope)}. This form
     *     collapses a refused token and an account with no Minecraft profile onto the same {@code null},
     *     which are different problems with different answers.
     */
    @Deprecated(since = "0.8.0")
    public static String[] fetchProfileFromToken(String token, String profileUrl) throws Exception {
        ProfileLookup lookup = fetchProfile(token, profileUrl, NetworkScope.of(NetworkScope.Purpose.PROFILE));
        return lookup.found() ? new String[] {lookup.username(), lookup.uuid()} : null;
    }

    /**
     * Validates a Minecraft access token and resolves the identity behind it, reporting the status
     * either way.
     *
     * <p>The status is the point. A 401 means the token is spent and renewal is worth trying; a 404 means
     * the credentials are fine and the account simply has no Minecraft profile, which no amount of
     * re-authenticating will change; a 5xx means try again later. Reported as one {@code null}, those
     * three produce the same unhelpful message for problems with nothing in common.
     *
     * @param token the Minecraft/Bearer access token to validate
     * @param profileUrl the Minecraft services profile endpoint
     * @param scope what is being fetched, and on whose behalf
     * @return the lookup outcome
     * @throws Exception if a network error occurs during the request
     * @since 0.8.0
     */
    public static ProfileLookup fetchProfile(String token, String profileUrl, NetworkScope scope) throws Exception {
        HttpUtil.HttpResponse response =
                HttpUtil.getForStatus(profileUrl, Map.of("Authorization", "Bearer " + token), scope);

        JsonObject body = response.body();
        if (response.successful() && body != null && body.has("name") && body.has("id")) {
            return new ProfileLookup(
                    response.status(),
                    body.get("name").getAsString(),
                    body.get("id").getAsString(),
                    response.retryAfter());
        }
        return new ProfileLookup(response.status(), null, null, response.retryAfter());
    }
}
