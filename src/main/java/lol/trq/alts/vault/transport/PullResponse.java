package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;

/**
 * The result of a pull: the current manifest, the current encrypted payload (null when
 * {@code unchanged} is true), and whether the client's known version was already current.
 *
 * @param manifest the current repository manifest
 * @param envelope the current encrypted payload, or null if unchanged
 * @param unchanged true if the client's known version is already current
 * @author trq
 * @since 0.2.0
 */
public record PullResponse(
        @SerializedName("manifest") VaultManifest manifest,
        @SerializedName("envelope") EncryptedEnvelope envelope,
        @SerializedName("unchanged") boolean unchanged) {}
