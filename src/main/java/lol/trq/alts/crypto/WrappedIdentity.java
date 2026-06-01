package lol.trq.alts.crypto;

import com.google.gson.annotations.SerializedName;
import java.util.Objects;

/**
 * The server-storable, passphrase-protected form of a {@link VaultIdentity}. Holds the two public keys
 * in the clear (they are public) plus the private key material sealed under a passphrase-derived key,
 * and optionally a second sealing of the same material under an offline recovery code. The server, or
 * anyone without the passphrase or recovery code, learns nothing exploitable from this record.
 *
 * @param ed25519PublicKey the identity (signing) public key, Base64 raw 32 bytes
 * @param x25519PublicKey the key-agreement public key, Base64 raw 32 bytes
 * @param kdfSalt the PBKDF2 salt for the passphrase-derived master key, Base64
 * @param kdfIterations the PBKDF2 iteration count
 * @param wrappedPrivateMaterial the private key material sealed under the master key ({@code iv||ct}),
 *     Base64
 * @param recoverySalt the PBKDF2 salt for the recovery-code key, Base64, or null if no recovery is set
 * @param recoveryWrappedPrivateMaterial the private material sealed under the recovery key
 *     ({@code iv||ct}), Base64, or null if no recovery is set
 * @author trq
 * @since 0.2.0
 */
public record WrappedIdentity(
        @SerializedName("ed25519PublicKey") String ed25519PublicKey,
        @SerializedName("x25519PublicKey") String x25519PublicKey,
        @SerializedName("kdfSalt") String kdfSalt,
        @SerializedName("kdfIterations") int kdfIterations,
        @SerializedName("wrappedPrivateMaterial") String wrappedPrivateMaterial,
        @SerializedName("recoverySalt") String recoverySalt,
        @SerializedName("recoveryWrappedPrivateMaterial") String recoveryWrappedPrivateMaterial) {

    /** Validates that the always-present components are non-null. */
    public WrappedIdentity {
        Objects.requireNonNull(ed25519PublicKey, "ed25519PublicKey");
        Objects.requireNonNull(x25519PublicKey, "x25519PublicKey");
        Objects.requireNonNull(kdfSalt, "kdfSalt");
        Objects.requireNonNull(wrappedPrivateMaterial, "wrappedPrivateMaterial");
    }

    /**
     * Returns whether this identity carries an offline recovery sealing.
     *
     * @return true if a recovery code can restore this identity
     */
    public boolean hasRecovery() {
        return recoverySalt != null && recoveryWrappedPrivateMaterial != null;
    }
}
