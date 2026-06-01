package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Removes a member from a repository. Removal is not just a roster delete: the remaining members rotate
 * the data key, re-encrypt the payload under the new epoch, and re-wrap the new key to themselves, so
 * the removed member's old wrapped key is useless against future payloads. All of that is computed
 * client-side and submitted atomically here.
 *
 * @param repoId the repository
 * @param removedMemberId the Ed25519 id of the member being removed
 * @param rotatedEnvelope the payload re-encrypted under the new key epoch
 * @param rewrappedMembers the remaining members with the new data key wrapped to each
 * @param newKeyEpoch the new key epoch after rotation
 * @author trq
 * @since 0.2.0
 */
public record MemberRemoveRequest(
        @SerializedName("repoId") String repoId,
        @SerializedName("removedMemberId") String removedMemberId,
        @SerializedName("rotatedEnvelope") EncryptedEnvelope rotatedEnvelope,
        @SerializedName("rewrappedMembers") List<MemberEntry> rewrappedMembers,
        @SerializedName("newKeyEpoch") long newKeyEpoch) {}
