package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;
import java.util.Objects;
import lol.trq.alts.crypto.WrappedKey;

/**
 * A repository member as the server stores it: the two public keys plus the repository data key wrapped
 * for this member at a given key epoch. The server can read none of it — the wrapped key is opaque
 * without the member's private key.
 *
 * <p>{@code keyBindingSig} is an optional anti-MITM binding: a signature, by an identity provider the
 * other members trust, over this member's two public keys. In a federated deployment a member may be
 * invited on a server it does not itself operate, so before wrapping the data key to keys the server
 * served, a member can require an {@link IssuerSignedKeyBindingVerifier} to check this signature. It is
 * {@code null} when no binding is published; the server stores and serves it opaquely.
 *
 * @param ed25519PublicKey the member's Ed25519 identity key, Base64 (the member id)
 * @param x25519PublicKey the member's X25519 key-agreement key, Base64
 * @param wrappedDataKey the repository data key wrapped to this member
 * @param keyEpoch the key-rotation epoch the wrapped key belongs to
 * @param keyBindingSig an optional issuer signature binding the two public keys to this identity,
 *     Base64, or {@code null} when none is published
 * @author trq
 * @since 0.2.0
 */
public record MemberEntry(
        @SerializedName("ed25519PublicKey") String ed25519PublicKey,
        @SerializedName("x25519PublicKey") String x25519PublicKey,
        @SerializedName("wrappedDataKey") WrappedKey wrappedDataKey,
        @SerializedName("keyEpoch") long keyEpoch,
        @SerializedName("keyBindingSig") String keyBindingSig) {

    /** Validates the required components ({@code keyBindingSig} is optional and left untouched). */
    public MemberEntry {
        Objects.requireNonNull(ed25519PublicKey, "ed25519PublicKey");
        Objects.requireNonNull(x25519PublicKey, "x25519PublicKey");
        Objects.requireNonNull(wrappedDataKey, "wrappedDataKey");
    }

    /**
     * Creates an entry with no key-binding signature.
     *
     * @param ed25519PublicKey the member's Ed25519 identity key, Base64
     * @param x25519PublicKey the member's X25519 key-agreement key, Base64
     * @param wrappedDataKey the repository data key wrapped to this member
     * @param keyEpoch the key-rotation epoch the wrapped key belongs to
     */
    public MemberEntry(String ed25519PublicKey, String x25519PublicKey, WrappedKey wrappedDataKey, long keyEpoch) {
        this(ed25519PublicKey, x25519PublicKey, wrappedDataKey, keyEpoch, null);
    }

    /**
     * Returns a copy of this entry carrying the given key-binding signature.
     *
     * @param keyBindingSig the issuer signature over the two public keys, Base64, or {@code null}
     * @return a copy with {@code keyBindingSig} replaced
     */
    public MemberEntry withKeyBindingSig(String keyBindingSig) {
        return new MemberEntry(ed25519PublicKey, x25519PublicKey, wrappedDataKey, keyEpoch, keyBindingSig);
    }
}
