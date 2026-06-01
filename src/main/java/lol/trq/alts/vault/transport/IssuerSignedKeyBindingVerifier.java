package lol.trq.alts.vault.transport;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Objects;
import lol.trq.alts.crypto.CryptoException;
import lol.trq.alts.crypto.Ed25519;
import lol.trq.alts.vault.MemberPublicKey;
import lol.trq.alts.vault.VaultException;

/**
 * A {@link KeyBindingVerifier} that trusts a member's served public keys only if they carry a valid
 * signature from an identity provider the verifier was constructed with. This is the federated
 * anti-MITM check: a member invited on a server it does not operate cannot be tricked into wrapping the
 * repository data key to attacker-substituted keys, because the issuer's signature over the genuine key
 * pair would not validate.
 *
 * <p>The signed message is canonical and deterministic: the member's two Base64 public keys joined by a
 * single {@code '|'}, UTF-8 encoded (see {@link #bindingMessage}). The issuer signs that with Ed25519;
 * this verifier checks {@link MemberEntry#keyBindingSig()} against it. The host obtains the issuer key
 * out of band (for example from the issuer JWKS URL carried in a {@code RepoLocator}); this class never
 * performs network I/O and so names no server.
 *
 * @author trq
 * @since 0.2.0
 */
public final class IssuerSignedKeyBindingVerifier implements KeyBindingVerifier {

    private final PublicKey issuerKey;

    /**
     * Creates a verifier that checks bindings against the given issuer Ed25519 public key.
     *
     * @param issuerKey the issuer's Ed25519 public key
     */
    public IssuerSignedKeyBindingVerifier(PublicKey issuerKey) {
        this.issuerKey = Objects.requireNonNull(issuerKey, "issuerKey");
    }

    /**
     * Creates a verifier from a raw 32-byte Ed25519 issuer public key.
     *
     * @param rawIssuerKey the issuer's Ed25519 public key, raw 32 bytes (RFC 8032)
     * @return the verifier
     * @throws CryptoException if the key cannot be decoded
     */
    public static IssuerSignedKeyBindingVerifier ofRawKey(byte[] rawIssuerKey) throws CryptoException {
        return new IssuerSignedKeyBindingVerifier(Ed25519.decodePublicKey(rawIssuerKey));
    }

    @Override
    public MemberPublicKey verify(String repoId, MemberEntry entry) throws VaultException {
        Objects.requireNonNull(entry, "entry");
        String sig = entry.keyBindingSig();
        if (sig == null || sig.isBlank()) {
            throw new VaultException("member " + entry.ed25519PublicKey() + " has no key-binding signature to verify");
        }
        byte[] message = bindingMessage(entry.ed25519PublicKey(), entry.x25519PublicKey());
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(sig);
        } catch (IllegalArgumentException e) {
            throw new VaultException("member " + entry.ed25519PublicKey() + " key-binding signature is not Base64");
        }
        if (!Ed25519.verify(issuerKey, message, signature)) {
            throw new VaultException(
                    "key-binding signature failed verification for member " + entry.ed25519PublicKey());
        }
        return new MemberPublicKey(entry.ed25519PublicKey(), entry.x25519PublicKey());
    }

    /**
     * The canonical bytes the issuer signs to bind a member's two public keys to their identity: the
     * Base64 Ed25519 key, a {@code '|'} separator, then the Base64 X25519 key, UTF-8 encoded.
     *
     * @param ed25519PublicKey the member's Ed25519 identity key, Base64
     * @param x25519PublicKey the member's X25519 key-agreement key, Base64
     * @return the canonical message bytes
     */
    public static byte[] bindingMessage(String ed25519PublicKey, String x25519PublicKey) {
        return (ed25519PublicKey + "|" + x25519PublicKey).getBytes(StandardCharsets.UTF_8);
    }
}
