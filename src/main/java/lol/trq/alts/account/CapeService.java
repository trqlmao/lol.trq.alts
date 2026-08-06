package lol.trq.alts.account;

import java.util.List;

/**
 * Shows or hides the account's capes. The account's owned capes come from the profile read
 * ({@link ProfileService#fetch()} → {@link PlayerProfile#capes()}), so there is no separate list call.
 *
 * @author trq
 * @since 1.0.0
 */
public interface CapeService {

    /**
     * Returns the capes the account owns, as a convenience over reading them off the profile.
     *
     * @return the owned capes
     * @throws AccountException if the token was refused or the service failed
     * @since 1.0.0
     */
    List<Cape> owned() throws AccountException;

    /**
     * Shows the cape with the given id.
     *
     * @param capeId the cape id (from {@link Cape#id()})
     * @return the updated profile
     * @throws AccountException if the token was refused or the cape is not owned
     * @since 1.0.0
     */
    PlayerProfile setActive(String capeId) throws AccountException;

    /**
     * Hides whatever cape is shown.
     *
     * @throws AccountException if the token was refused or the service failed
     * @since 1.0.0
     */
    void hide() throws AccountException;
}
