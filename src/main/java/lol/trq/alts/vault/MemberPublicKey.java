package lol.trq.alts.vault;

import com.google.gson.annotations.SerializedName;
import java.util.Objects;

/**
 * The public half of a prospective member's identity, as needed to invite them to a repository: their
 * Ed25519 identity key (which doubles as their member id) and the X25519 key the repository data key is
 * wrapped to. Obtained out-of-band or via the transport's member-key lookup.
 *
 * @param ed25519PublicKey the member's Ed25519 identity public key, Base64 raw 32 bytes (the member id)
 * @param x25519PublicKey the member's X25519 key-agreement public key, Base64 raw 32 bytes
 * @author trq
 * @since 0.2.0
 */
public record MemberPublicKey(
        @SerializedName("ed25519PublicKey") String ed25519PublicKey,
        @SerializedName("x25519PublicKey") String x25519PublicKey) {

    /** Validates both keys are present. */
    public MemberPublicKey {
        Objects.requireNonNull(ed25519PublicKey, "ed25519PublicKey");
        Objects.requireNonNull(x25519PublicKey, "x25519PublicKey");
    }
}
