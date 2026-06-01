package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Objects;

/**
 * The non-secret metadata describing a repository: its id, the key-wrap scheme in force, the current
 * key epoch and payload version, and the member roster (each with their wrapped data key). Everything
 * here is safe for the server to hold and serve; the actual alts ride separately in an
 * {@link EncryptedEnvelope}.
 *
 * @param repoId the repository identifier
 * @param schemeId the key-wrap scheme id (see {@link lol.trq.alts.crypto.KeyWrapScheme#schemeId()})
 * @param keyEpoch the current key-rotation epoch
 * @param payloadVersion the current payload version
 * @param members the member roster
 * @author trq
 * @since 0.2.0
 */
public record VaultManifest(
        @SerializedName("repoId") String repoId,
        @SerializedName("schemeId") String schemeId,
        @SerializedName("keyEpoch") long keyEpoch,
        @SerializedName("payloadVersion") long payloadVersion,
        @SerializedName("members") List<MemberEntry> members) {

    /** Validates the required components. */
    public VaultManifest {
        Objects.requireNonNull(repoId, "repoId");
        Objects.requireNonNull(schemeId, "schemeId");
        Objects.requireNonNull(members, "members");
    }

    /**
     * Finds the member entry for the given Ed25519 identity, if present.
     *
     * @param ed25519PublicKey the member id to look up
     * @return the entry, or null if this id is not a member
     */
    public MemberEntry findMember(String ed25519PublicKey) {
        for (MemberEntry entry : members) {
            if (entry.ed25519PublicKey().equals(ed25519PublicKey)) {
                return entry;
            }
        }
        return null;
    }
}
