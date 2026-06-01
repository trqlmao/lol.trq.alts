package lol.trq.alts.auth;

/**
 * The resolved Minecraft account profile produced by an authentication flow.
 *
 * @param username the player's Minecraft username
 * @param uuid the player's account UUID
 * @param accessToken the Minecraft session access token
 * @author trq
 * @since 0.1.0
 */
public record MinecraftProfile(String username, String uuid, String accessToken) {}
