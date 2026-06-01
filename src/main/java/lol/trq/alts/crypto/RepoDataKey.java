package lol.trq.alts.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * A repository data key (RDK): the AES-256 key that encrypts a repository's alt payload, tagged with
 * its rotation {@code epoch}. The RDK never travels in the clear — it is wrapped to each member's
 * X25519 public key via a {@link KeyWrapScheme}. Rotating a repo (for example after removing a member)
 * generates a fresh RDK at the next epoch.
 *
 * @author trq
 * @since 0.2.0
 */
public final class RepoDataKey {

    private final SecretKey key;
    private final long epoch;

    private RepoDataKey(SecretKey key, long epoch) {
        this.key = key;
        this.epoch = epoch;
    }

    /**
     * Generates a fresh random RDK at epoch 0 (a new repository).
     *
     * @return a new data key
     * @throws CryptoException if key generation fails
     */
    public static RepoDataKey generate() throws CryptoException {
        return generate(0L);
    }

    /**
     * Generates a fresh random RDK at the given epoch (a rotation).
     *
     * @param epoch the rotation epoch this key belongs to
     * @return a new data key
     * @throws CryptoException if key generation fails
     */
    public static RepoDataKey generate(long epoch) throws CryptoException {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            return new RepoDataKey(kg.generateKey(), epoch);
        } catch (Exception e) {
            throw new CryptoException("RDK generation failed", e);
        }
    }

    /**
     * Reconstructs an RDK from an existing AES key and epoch (after unwrapping).
     *
     * @param key the AES key
     * @param epoch the epoch this key belongs to
     * @return the data key
     */
    public static RepoDataKey of(SecretKey key, long epoch) {
        return new RepoDataKey(key, epoch);
    }

    /**
     * Wraps this RDK to a recipient's X25519 public key, producing a per-member blob safe to store on
     * the server.
     *
     * @param scheme the key-wrap scheme
     * @param recipientPublicKey the recipient's X25519 public key
     * @return the wrapped data key
     * @throws CryptoException if wrapping fails
     */
    public WrappedKey wrapTo(KeyWrapScheme scheme, PublicKey recipientPublicKey) throws CryptoException {
        return scheme.wrap(key, recipientPublicKey);
    }

    /**
     * Unwraps an RDK addressed to this party.
     *
     * @param scheme the key-wrap scheme
     * @param blob the wrapped data key
     * @param recipientPrivateKey this party's X25519 private key
     * @param epoch the epoch this wrapped key belongs to
     * @return the recovered data key
     * @throws CryptoException if unwrapping fails
     */
    public static RepoDataKey unwrap(KeyWrapScheme scheme, WrappedKey blob, PrivateKey recipientPrivateKey, long epoch)
            throws CryptoException {
        return new RepoDataKey(scheme.unwrap(blob, recipientPrivateKey), epoch);
    }

    /**
     * Returns the underlying AES key.
     *
     * @return the secret key
     */
    public SecretKey secretKey() {
        return key;
    }

    /**
     * Returns the rotation epoch this key belongs to.
     *
     * @return the epoch
     */
    public long epoch() {
        return epoch;
    }
}
