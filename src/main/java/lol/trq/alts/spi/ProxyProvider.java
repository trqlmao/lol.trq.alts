package lol.trq.alts.spi;

import lol.trq.alts.net.NetworkScope;
import lol.trq.alts.net.ProxyRoute;

/**
 * Host seam deciding how each request reaches the network. Optional: without one, every request is
 * direct, which is the behaviour of every release before this seam existed.
 *
 * <p>The library asks per request, so any rotation policy a host likes — round-robin, per-account
 * pinning, health-checked pools — is implemented behind this method. Acquiring proxies, and knowing
 * whether one is alive, stays the host's business.
 *
 * <p><strong>Resolution fails closed.</strong> Once a provider is installed, returning {@code null} or
 * throwing makes the request fail rather than fall back to a direct connection. The intuitive fallback
 * is the wrong one here: the reason to proxy an alt manager is that its accounts must not all look like
 * one machine, so a silent fallback discloses the real address at exactly the moment the host believed
 * it was covered. A failed request is recoverable; a disclosed address is not. Say "direct is fine here"
 * by returning {@link ProxyRoute#direct()}.
 *
 * @author trq
 * @since 0.8.0
 */
@FunctionalInterface
public interface ProxyProvider {

    /**
     * Returns the route the given request should take.
     *
     * @param scope what is being fetched, and on whose behalf
     * @return the route to use; {@link ProxyRoute#direct()} for no proxy. Never {@code null}
     * @since 0.8.0
     */
    ProxyRoute routeFor(NetworkScope scope);
}
