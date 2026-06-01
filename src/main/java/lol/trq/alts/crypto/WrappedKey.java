package lol.trq.alts.crypto;

import com.google.gson.annotations.SerializedName;
import java.util.Objects;

/**
 * A repository data key encrypted (wrapped) for a single recipient. This is the only form of a data
 * key the server ever stores: opaque to anyone without the recipient's private key. Every field is a
 * Base64 string so the blob serializes directly through Gson onto the wire.
 *
 * @param schemeId the key-wrap scheme that produced this blob (see {@link KeyWrapScheme#schemeId()})
 * @param ephemeralPublicKey the sender's ephemeral X25519 public key, Base64 raw 32 bytes
 * @param iv the AES-GCM IV, Base64 12 bytes
 * @param ciphertext the wrapped data key with its GCM tag, Base64
 * @author trq
 * @since 0.2.0
 */
public record WrappedKey(
        @SerializedName("schemeId") String schemeId,
        @SerializedName("ephemeralPublicKey") String ephemeralPublicKey,
        @SerializedName("iv") String iv,
        @SerializedName("ciphertext") String ciphertext) {

    /** Validates that all components are present. */
    public WrappedKey {
        Objects.requireNonNull(schemeId, "schemeId");
        Objects.requireNonNull(ephemeralPublicKey, "ephemeralPublicKey");
        Objects.requireNonNull(iv, "iv");
        Objects.requireNonNull(ciphertext, "ciphertext");
    }
}
