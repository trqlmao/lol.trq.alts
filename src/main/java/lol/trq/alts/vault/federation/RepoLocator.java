package lol.trq.alts.vault.federation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * The second half of a federated join handshake: where a repository lives and how to trust it, returned
 * by an existing member after they accept an {@link InviteRequest}. The joiner decodes it, resolves a
 * transport for {@link #host()}, authenticates with their own identity, pulls the manifest, and opens
 * the repository.
 *
 * <p>{@code schemeId} and {@code keyEpoch} are advisory hints — the authoritative values come from the
 * pulled manifest. {@code issuerJwksUrl} is the anti-MITM trust hint: the identity provider whose
 * signature an {@code IssuerSignedKeyBindingVerifier} should check on member key bindings, which matters
 * when the joiner does not itself operate the host. It is {@code null} when the deployment publishes no
 * issuer binding.
 *
 * @param version the token format version
 * @param host the authority of the server holding the repository
 * @param repoId the server-local repository id
 * @param schemeId the advisory key-wrap scheme id
 * @param keyEpoch the advisory current key epoch
 * @param issuerJwksUrl the issuer JWKS URL to trust for key bindings, or {@code null}
 * @author trq
 * @since 0.2.0
 */
public record RepoLocator(
        @SerializedName("v") int version,
        @SerializedName("host") String host,
        @SerializedName("repoId") String repoId,
        @SerializedName("schemeId") String schemeId,
        @SerializedName("keyEpoch") long keyEpoch,
        @SerializedName("issuerJwksUrl") String issuerJwksUrl) {

    /** The current token format version. */
    public static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder().create();

    /** Validates the required parts. */
    public RepoLocator {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(repoId, "repoId");
    }

    /**
     * Builds a locator at the current version.
     *
     * @param address the repository address
     * @param schemeId the advisory key-wrap scheme id
     * @param keyEpoch the advisory current key epoch
     * @param issuerJwksUrl the issuer JWKS URL to trust, or {@code null}
     * @return the locator
     */
    public static RepoLocator of(RepoAddress address, String schemeId, long keyEpoch, String issuerJwksUrl) {
        Objects.requireNonNull(address, "address");
        return new RepoLocator(VERSION, address.host(), address.repoId(), schemeId, keyEpoch, issuerJwksUrl);
    }

    /**
     * Returns the {@link RepoAddress} this locator points at.
     *
     * @return the repository address
     */
    public RepoAddress toRepoAddress() {
        return new RepoAddress(host, repoId);
    }

    /**
     * Encodes this locator as a base64url token (no padding).
     *
     * @return the encoded token
     */
    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a base64url token produced by {@link #encode()}.
     *
     * @param token the encoded token
     * @return the decoded locator
     * @throws IllegalArgumentException if the token is not a well-formed locator
     */
    public static RepoLocator decode(String token) {
        Objects.requireNonNull(token, "token");
        try {
            byte[] json = Base64.getUrlDecoder().decode(token);
            RepoLocator decoded = GSON.fromJson(new String(json, StandardCharsets.UTF_8), RepoLocator.class);
            if (decoded == null || decoded.host == null || decoded.repoId == null) {
                throw new IllegalArgumentException("malformed repo locator token");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            // Gson runs the canonical constructor, whose requireNonNull throws NPE on missing fields;
            // a bad JSON shape throws JsonSyntaxException. Both mean a malformed token.
            throw new IllegalArgumentException("malformed repo locator token", e);
        }
    }
}
