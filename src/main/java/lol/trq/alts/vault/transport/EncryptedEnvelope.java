package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;
import java.util.Objects;

/**
 * The encrypted alt payload for a repository at one point in time. The header fields are not just
 * metadata — they are recomputed into the AES-GCM additional authenticated data, so a server cannot
 * splice a payload across repositories, replay an older version, or downgrade the key epoch without
 * failing the authentication tag on decrypt.
 *
 * @param repoId the repository this payload belongs to
 * @param payloadVersion the monotonic version of this payload
 * @param keyEpoch the data-key epoch this payload was encrypted under
 * @param iv the AES-GCM IV, Base64 12 bytes
 * @param ciphertext the encrypted payload with its GCM tag, Base64
 * @author trq
 * @since 0.2.0
 */
public record EncryptedEnvelope(
        @SerializedName("repoId") String repoId,
        @SerializedName("payloadVersion") long payloadVersion,
        @SerializedName("keyEpoch") long keyEpoch,
        @SerializedName("iv") String iv,
        @SerializedName("ciphertext") String ciphertext) {

    /** Validates the required components. */
    public EncryptedEnvelope {
        Objects.requireNonNull(repoId, "repoId");
        Objects.requireNonNull(iv, "iv");
        Objects.requireNonNull(ciphertext, "ciphertext");
    }
}
