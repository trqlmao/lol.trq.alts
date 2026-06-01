package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;

/**
 * Requests the current manifest and payload for a repository. The server may answer {@code unchanged}
 * if the client's known version is already current.
 *
 * @param repoId the repository to pull
 * @param knownPayloadVersion the payload version the client already holds, or 0 if none
 * @author trq
 * @since 0.2.0
 */
public record PullRequest(
        @SerializedName("repoId") String repoId,
        @SerializedName("knownPayloadVersion") long knownPayloadVersion) {}
