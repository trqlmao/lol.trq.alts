package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;

/**
 * Redeems a signed challenge for an access token. The server verifies that {@code signature} is a valid
 * Ed25519 signature over {@code nonce} by {@code ed25519PublicKey}, then mints a token scoped to that
 * identity. No account or password is involved — the keypair is the identity.
 *
 * @param ed25519PublicKey the identity, Base64 raw 32 bytes
 * @param nonce the challenge nonce received from the server, Base64
 * @param signature the Ed25519 signature over the nonce, Base64
 * @author trq
 * @since 0.2.0
 */
public record TokenRequest(
        @SerializedName("ed25519PublicKey") String ed25519PublicKey,
        @SerializedName("nonce") String nonce,
        @SerializedName("signature") String signature) {}
