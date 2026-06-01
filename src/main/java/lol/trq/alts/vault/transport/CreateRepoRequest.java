package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;

/**
 * Creates a new repository on the server from a locally-built manifest (with the creator as sole
 * member) and the initial encrypted payload.
 *
 * @param manifest the seed manifest (creator membership, scheme, epoch 0)
 * @param initialEnvelope the initial encrypted alt payload
 * @author trq
 * @since 0.2.0
 */
public record CreateRepoRequest(
        @SerializedName("manifest") VaultManifest manifest,
        @SerializedName("initialEnvelope") EncryptedEnvelope initialEnvelope) {}
