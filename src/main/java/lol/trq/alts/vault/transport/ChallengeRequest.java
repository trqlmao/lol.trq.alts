package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;

/**
 * Asks the server to issue an authentication challenge for the given Ed25519 identity.
 *
 * @param ed25519PublicKey the identity requesting a challenge, Base64 raw 32 bytes
 * @author trq
 * @since 0.2.0
 */
public record ChallengeRequest(
        @SerializedName("ed25519PublicKey") String ed25519PublicKey) {}
