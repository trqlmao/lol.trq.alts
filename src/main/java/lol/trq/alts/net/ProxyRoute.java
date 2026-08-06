package lol.trq.alts.net;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * How one request should reach the network: directly, or through a proxy the host names.
 *
 * <p>A route is deliberately not stored on an account. A proxy is infrastructure rather than account
 * identity, and — decisively — {@link lol.trq.alts.model.AltAccount} travels into shared vault
 * repositories, so a route living there would publish a paid proxy's host, port, and credentials to
 * every member of every repository the alt is shared into.
 *
 * <p><strong>Authenticated SOCKS5 is not supported.</strong> The JDK's SOCKS client reads credentials
 * from process-global state — the {@code java.net.socks.*} system properties, or the default
 * {@link java.net.Authenticator} — and a library that installs either has reached into its host's JVM to
 * do it. {@link #withCredentials(String, char[])} therefore throws on a SOCKS5 route rather than
 * silently connecting unauthenticated, which would send the request from an address the host did not
 * choose. Unauthenticated SOCKS5 works; front an authenticated one with a local HTTP proxy.
 *
 * @param kind how to reach the network
 * @param host the proxy host, or {@code null} for {@link Kind#DIRECT}
 * @param port the proxy port, or {@code 0} for {@link Kind#DIRECT}
 * @param username the proxy username, or {@code null} when unauthenticated
 * @param password the proxy password, or {@code null} when unauthenticated
 * @author trq
 * @since 0.8.0
 */
public record ProxyRoute(Kind kind, String host, int port, String username, char[] password) {

    /**
     * How a route reaches the network.
     *
     * @author trq
     * @since 0.8.0
     */
    public enum Kind {
        /** No proxy. */
        DIRECT,

        /** An HTTP proxy, which may carry credentials. */
        HTTP,

        /** A SOCKS5 proxy, which may not — see {@link ProxyRoute}. */
        SOCKS5
    }

    private static final ProxyRoute DIRECT = new ProxyRoute(Kind.DIRECT, null, 0, null, null);

    /** Validates the shape, and refuses the credential combination the JDK cannot honour per-request. */
    public ProxyRoute {
        if (kind == null) {
            throw new IllegalArgumentException("proxy kind is required");
        }
        if (kind == Kind.DIRECT) {
            host = null;
            port = 0;
            username = null;
            password = null;
        } else {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("proxy host is required for a " + kind + " route");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("proxy port out of range: " + port);
            }
            if (kind == Kind.SOCKS5 && username != null) {
                throw new UnsupportedOperationException(
                        "authenticated SOCKS5 is not supported: the JDK reads SOCKS credentials from "
                                + "process-global state. Front the proxy with a local HTTP proxy instead.");
            }
        }
    }

    /**
     * Returns the route meaning "no proxy".
     *
     * @return the direct route
     * @since 0.8.0
     */
    public static ProxyRoute direct() {
        return DIRECT;
    }

    /**
     * Creates an unauthenticated HTTP proxy route.
     *
     * @param host the proxy host
     * @param port the proxy port
     * @return the route
     * @since 0.8.0
     */
    public static ProxyRoute http(String host, int port) {
        return new ProxyRoute(Kind.HTTP, host, port, null, null);
    }

    /**
     * Creates an unauthenticated SOCKS5 proxy route.
     *
     * @param host the proxy host
     * @param port the proxy port
     * @return the route
     * @since 0.8.0
     */
    public static ProxyRoute socks5(String host, int port) {
        return new ProxyRoute(Kind.SOCKS5, host, port, null, null);
    }

    /**
     * Returns a copy carrying proxy credentials.
     *
     * @param username the proxy username
     * @param password the proxy password
     * @return a copy that authenticates
     * @throws UnsupportedOperationException if this is a SOCKS5 route — see {@link ProxyRoute}
     * @throws IllegalStateException if this is the direct route, which authenticates against nothing
     * @since 0.8.0
     */
    public ProxyRoute withCredentials(String username, char[] password) {
        if (kind == Kind.DIRECT) {
            throw new IllegalStateException("the direct route has nothing to authenticate against");
        }
        return new ProxyRoute(kind, host, port, username, password == null ? null : password.clone());
    }

    /**
     * Returns whether this route reaches the network without a proxy.
     *
     * @return true if direct
     * @since 0.8.0
     */
    public boolean isDirect() {
        return kind == Kind.DIRECT;
    }

    /**
     * Returns whether this route carries credentials.
     *
     * @return true if a username is set
     * @since 0.8.0
     */
    public boolean authenticated() {
        return username != null;
    }

    /**
     * Returns the source address this route presents to a service, as a stable key. Value identity for a
     * route is its endpoint rather than the whole record, because what a service rate-limits is the
     * address, not the credential that reached it.
     *
     * @return a key identifying the endpoint
     * @since 0.8.0
     */
    public String endpoint() {
        return isDirect() ? "DIRECT" : kind + "://" + host + ":" + port;
    }

    /**
     * Returns this route as a JDK proxy.
     *
     * @return the proxy, or {@link Proxy#NO_PROXY} for the direct route
     * @since 0.8.0
     */
    public Proxy toProxy() {
        if (isDirect()) {
            return Proxy.NO_PROXY;
        }
        Proxy.Type type = kind == Kind.SOCKS5 ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, InetSocketAddress.createUnresolved(host, port));
    }

    /**
     * Returns the {@code Proxy-Authorization} value for this route. Set per connection, so no global
     * authenticator is installed.
     *
     * @return the header value, or {@code null} when the route does not authenticate
     * @since 0.8.0
     */
    public String authorizationHeader() {
        if (!authenticated()) {
            return null;
        }
        String pair = username + ":" + (password == null ? "" : new String(password));
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns a description carrying no credential. The generated {@code toString} would print the
     * password array, and a host logging the route it chose would write a proxy credential to its log.
     *
     * @return a loggable description
     * @since 0.8.0
     */
    @Override
    public String toString() {
        return "ProxyRoute[" + endpoint() + (authenticated() ? ", authenticated" : "") + "]";
    }
}
