package lol.trq.alts.account;

/**
 * Changes the account's skin.
 *
 * @author trq
 * @since 1.0.0
 */
public interface SkinService {

    /**
     * Sets the skin from a texture URL Mojang will fetch.
     *
     * @param url the skin texture URL
     * @param model the arm model to render it with
     * @return the updated profile
     * @throws AccountException if the token was refused or the service rejected the skin
     * @since 1.0.0
     */
    PlayerProfile setFromUrl(String url, SkinModel model) throws AccountException;

    /**
     * Uploads a skin from PNG bytes.
     *
     * @param pngBytes the skin PNG
     * @param model the arm model to render it with
     * @return the updated profile
     * @throws AccountException if the token was refused or the service rejected the skin
     * @since 1.0.0
     */
    PlayerProfile upload(byte[] pngBytes, SkinModel model) throws AccountException;

    /**
     * Resets the skin to the default, removing any custom one.
     *
     * @throws AccountException if the token was refused or the service failed
     * @since 1.0.0
     */
    void reset() throws AccountException;
}
