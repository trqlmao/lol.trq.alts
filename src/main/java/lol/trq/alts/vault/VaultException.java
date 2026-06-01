package lol.trq.alts.vault;

/**
 * A vault-level failure that is not purely cryptographic: a missing membership, a rollback attempt, a
 * malformed manifest. Cryptographic failures surface as {@link lol.trq.alts.crypto.CryptoException}.
 *
 * @author trq
 * @since 0.2.0
 */
public final class VaultException extends Exception {

    /**
     * Creates a vault exception with a message.
     *
     * @param message the detail message
     */
    public VaultException(String message) {
        super(message);
    }

    /**
     * Creates a vault exception wrapping a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
