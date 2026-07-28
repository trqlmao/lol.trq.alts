package lol.trq.alts.auth;

/**
 * The resolved Minecraft account profile produced by an authentication flow.
 *
 * @param username the player's Minecraft username
 * @param uuid the player's account UUID
 * @param accessToken the Minecraft session access token
 * @param refreshToken the OAuth refresh token to persist for renewal, or {@code null} when the flow
 *     produced none
 * @param expiresAt the epoch-millis expiry of {@code accessToken}, or {@code 0} when unknown
 * @author trq
 * @since 0.1.0
 */
public record MinecraftProfile(String username, String uuid, String accessToken, String refreshToken, long expiresAt) {

    /**
     * Returns a description of this profile with both credentials redacted, so a host that logs the
     * outcome of an authentication flow does not write a live credential to disk.
     *
     * @return a loggable description carrying no credential
     */
    @Override
    public String toString() {
        return "MinecraftProfile[username=" + username + ", uuid=" + uuid + ", accessToken="
                + (accessToken == null ? "null" : "<redacted>") + ", refreshToken="
                + (refreshToken == null ? "null" : "<redacted>") + ", expiresAt=" + expiresAt + "]";
    }
}
