package lol.trq.alts.crypto;

import java.security.GeneralSecurityException;

/**
 * Single typed failure for the crypto layer: a wrong key, an unknown key-wrap scheme, an AEAD tag
 * mismatch, or any underlying JCA error. Callers catch this one type rather than the broad
 * {@link GeneralSecurityException} family.
 *
 * @author trq
 * @since 0.2.0
 */
public final class CryptoException extends GeneralSecurityException {

    /**
     * Creates a crypto exception with a message.
     *
     * @param message the detail message
     */
    public CryptoException(String message) {
        super(message);
    }

    /**
     * Creates a crypto exception wrapping a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
