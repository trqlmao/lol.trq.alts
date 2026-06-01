package lol.trq.alts.vault.federation;

import java.util.Objects;

/**
 * A federated, globally-resolvable address for a shared repository: the host of the server that holds
 * it plus the server-local repository id. Rendered as the URI {@code avp://<host>/<repoId>}.
 *
 * <p>The repository id stays exactly the opaque value the server minted — folding the host into the id
 * would change the on-the-wire manifest and break existing repositories, so the host lives only in the
 * address. A repository is globally identified by the {@code (host, repoId)} pair; the id alone is
 * unique only within one host. To reach a repository, a client resolves a transport for {@link #host()}
 * (see {@code VaultTransportResolver}) and authenticates there with its own identity.
 *
 * @param host the authority of the server holding the repository ({@code host} or {@code host:port})
 * @param repoId the server-local repository id
 * @author trq
 * @since 0.2.0
 */
public record RepoAddress(String host, String repoId) {

    /** The federation URI scheme prefix. */
    public static final String SCHEME = "avp://";

    /** Validates both parts are present and non-blank. */
    public RepoAddress {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(repoId, "repoId");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host is blank");
        }
        if (repoId.isBlank()) {
            throw new IllegalArgumentException("repoId is blank");
        }
    }

    /**
     * Renders this address as an {@code avp://host/repoId} URI.
     *
     * @return the URI form
     */
    public String toUri() {
        return SCHEME + host + "/" + repoId;
    }

    /**
     * Parses an {@code avp://host/repoId} URI.
     *
     * @param uri the URI to parse
     * @return the parsed address
     * @throws IllegalArgumentException if the URI is not a well-formed {@code avp://} address
     */
    public static RepoAddress parse(String uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.startsWith(SCHEME)) {
            throw new IllegalArgumentException("not an avp:// uri: " + uri);
        }
        String rest = uri.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            throw new IllegalArgumentException("malformed avp:// uri (expected avp://host/repoId): " + uri);
        }
        return new RepoAddress(rest.substring(0, slash), rest.substring(slash + 1));
    }

    @Override
    public String toString() {
        return toUri();
    }
}
