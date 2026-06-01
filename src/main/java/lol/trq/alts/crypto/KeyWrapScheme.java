package lol.trq.alts.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.SecretKey;

/**
 * Wraps and unwraps a repository data key to a recipient's public key. Implementations are identified
 * by a versioned {@link #schemeId()} carried in every {@link WrappedKey}, so the wire format can evolve
 * without ambiguity: an unwrapper rejects a blob whose scheme it does not recognize.
 *
 * @author trq
 * @since 0.2.0
 */
public interface KeyWrapScheme {

    /**
     * Returns the stable, versioned identifier for this scheme (for example
     * {@code "X25519-HKDF-SHA256-AESGCM-v1"}). Stored in each {@link WrappedKey#schemeId()}.
     *
     * @return the scheme identifier
     */
    String schemeId();

    /**
     * Wraps {@code dataKey} so that only the holder of the private key matching
     * {@code recipientPublicKey} can recover it.
     *
     * @param dataKey the repository data key to wrap
     * @param recipientPublicKey the recipient's X25519 public key
     * @return the wrapped data key
     * @throws CryptoException if wrapping fails
     */
    WrappedKey wrap(SecretKey dataKey, PublicKey recipientPublicKey) throws CryptoException;

    /**
     * Unwraps a data key wrapped to this party's public key.
     *
     * @param blob the wrapped data key
     * @param recipientPrivateKey the recipient's X25519 private key
     * @return the recovered data key
     * @throws CryptoException if the scheme id is unrecognized or unwrapping fails (wrong recipient,
     *     tampered blob)
     */
    SecretKey unwrap(WrappedKey blob, PrivateKey recipientPrivateKey) throws CryptoException;
}
