package lol.trq.alts.account;

/**
 * Reads an account's full Minecraft profile — name, UUID, skins, capes, and any pending moderation
 * actions. The slim name-and-UUID read the auth chain performs is separate and internal; this is the
 * whole picture a host shows.
 *
 * @author trq
 * @since 1.0.0
 */
public interface ProfileService {

    /**
     * Fetches the full profile.
     *
     * @return the profile
     * @throws AccountException if the token was refused or the service failed
     * @since 1.0.0
     */
    PlayerProfile fetch() throws AccountException;
}
