package lol.trq.alts.model;

/**
 * A transport-neutral description of a resolved account, handed to the host's
 * {@link lol.trq.alts.spi.SessionInjector} so it can install the session in whatever form its platform
 * uses. Deliberately free of any Minecraft or renderer type so the library stays consumer-agnostic.
 *
 * @param username the player's username
 * @param uuid the player's UUID, dashed
 * @param accessToken the session access token (empty for offline accounts)
 * @param type the protocol the account was authenticated with
 * @author trq
 * @since 0.1.0
 */
public record SessionData(String username, String uuid, String accessToken, AccountType type) {

    /**
     * Returns a description of this session with the access token redacted, so a host that logs what it
     * was handed does not write a live credential to disk.
     *
     * @return a loggable description carrying no credential
     */
    @Override
    public String toString() {
        return "SessionData[username=" + username + ", uuid=" + uuid + ", accessToken="
                + (accessToken == null ? "null" : "<redacted>") + ", type=" + type + "]";
    }
}
