package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;

/**
 * Adds a member to a repository. The data key wrapped to the new member is computed client-side by an
 * existing member; the server only records the entry — it cannot add members itself because it cannot
 * wrap the data key.
 *
 * @param repoId the repository to add the member to
 * @param member the new member entry (with the data key wrapped to them)
 * @author trq
 * @since 0.2.0
 */
public record MemberAddRequest(
        @SerializedName("repoId") String repoId,
        @SerializedName("member") MemberEntry member) {}
