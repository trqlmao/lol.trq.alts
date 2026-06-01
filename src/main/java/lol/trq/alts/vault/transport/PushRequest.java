package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Pushes a new encrypted payload for a repository, using optimistic concurrency:
 * {@code expectedPayloadVersion} must match the server's current version or the push is rejected as a
 * conflict, so concurrent writers cannot silently clobber each other. A push that accompanies a key
 * rotation also carries the re-wrapped member roster.
 *
 * @param repoId the repository to write
 * @param envelope the new encrypted payload
 * @param expectedPayloadVersion the version the client believes is current (its base)
 * @param rotatedMembers the re-wrapped member roster if this push rotates the key, else null
 * @author trq
 * @since 0.2.0
 */
public record PushRequest(
        @SerializedName("repoId") String repoId,
        @SerializedName("envelope") EncryptedEnvelope envelope,
        @SerializedName("expectedPayloadVersion") long expectedPayloadVersion,
        @SerializedName("rotatedMembers") List<MemberEntry> rotatedMembers) {}
