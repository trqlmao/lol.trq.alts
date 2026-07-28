package lol.trq.alts.vault;

import lol.trq.alts.crypto.RepoDataKey;
import lol.trq.alts.crypto.VaultIdentity;

/**
 * The in-memory working state for an opened repository: the repo id, the member's unlocked identity,
 * the unwrapped data key for the current epoch, and the payload version the member last saw. Held only
 * in memory while a repository is open; never serialized (the data key would leak).
 *
 * @param repoId the repository id
 * @param identity the member's unlocked identity
 * @param dataKey the unwrapped data key for the current epoch
 * @param payloadVersion the payload version this context is synced to
 * @param shareRefreshTokens whether this repository's manifest permits sharing OAuth refresh tokens;
 *     mirrors {@link lol.trq.alts.vault.transport.VaultManifest#shareRefreshTokens()} so the policy is
 *     enforced locally on every read and write
 * @author trq
 * @since 0.2.0
 */
public record RepoContext(
        String repoId, VaultIdentity identity, RepoDataKey dataKey, long payloadVersion, boolean shareRefreshTokens) {

    /**
     * Returns a copy advanced to a new payload version.
     *
     * @param version the new payload version
     * @return the updated context
     */
    public RepoContext withPayloadVersion(long version) {
        return new RepoContext(repoId, identity, dataKey, version, shareRefreshTokens);
    }

    /**
     * Returns a copy holding a rotated data key.
     *
     * @param rotated the new data key
     * @return the updated context
     */
    public RepoContext withDataKey(RepoDataKey rotated) {
        return new RepoContext(repoId, identity, rotated, payloadVersion, shareRefreshTokens);
    }
}
