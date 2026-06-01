package lol.trq.alts.spi;

import lol.trq.alts.vault.transport.VaultTransport;

/**
 * Host seam that maps a vault host authority (the {@code host} of an {@code avp://host/repoId} address)
 * to a concrete {@link VaultTransport} connected to that server. This is what makes shared repositories
 * federated: a member normally pointed at one server can reach a repository hosted on another simply by
 * resolving a transport for that repository's host and authenticating there with the same identity.
 *
 * <p>The host owns how authorities map to transports — which protocol profile (gRPC or HTTP/JSON) to
 * dial, connection pooling, and (critically) caching auth tokens per host so a token minted by one
 * server is never presented to another. Returning {@code null} signals the host is unwilling or unable
 * to talk to that authority.
 *
 * @author trq
 * @since 0.2.0
 */
@FunctionalInterface
public interface VaultTransportResolver {

    /**
     * Resolves a transport for the given vault host authority.
     *
     * @param host the host authority ({@code host} or {@code host:port}) from an {@code avp://} address
     * @return a transport connected to that host, or {@code null} if the host declines to connect
     */
    VaultTransport transportFor(String host);
}
