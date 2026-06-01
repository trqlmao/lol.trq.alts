package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;

/**
 * A server-issued challenge for keypair authentication: a random nonce the client must sign with its
 * Ed25519 identity key to prove key ownership.
 *
 * @param nonce the challenge nonce, Base64
 * @author trq
 * @since 0.2.0
 */
public record AuthChallenge(@SerializedName("nonce") String nonce) {}
