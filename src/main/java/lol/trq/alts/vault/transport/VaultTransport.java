package lol.trq.alts.vault.transport;

import java.util.concurrent.CompletableFuture;

/**
 * The network boundary for a shared repository, kept deliberately transport-neutral: the library
 * defines the contract in terms of plain DTOs and {@link CompletableFuture}s, and a host supplies the
 * concrete implementation (gRPC, HTTP, in-memory test double — whatever fits its ecosystem). The
 * library never names a server, an auth scheme, or a wire protocol.
 *
 * <p>The server backing an implementation is zero-knowledge: it stores and serves only the ciphertext
 * envelopes, the per-member wrapped keys, the public keys, and the version/epoch counters carried by
 * these DTOs. It can decrypt none of it.
 *
 * @author trq
 * @since 0.2.0
 */
public interface VaultTransport {

    /**
     * Requests an authentication challenge for an identity.
     *
     * @param request the challenge request
     * @return the issued challenge
     */
    CompletableFuture<AuthChallenge> challenge(ChallengeRequest request);

    /**
     * Redeems a signed challenge for an access token.
     *
     * @param request the signed token request
     * @return the access token
     */
    CompletableFuture<AuthToken> token(TokenRequest request);

    /**
     * Creates a new repository from a locally-built manifest and initial payload.
     *
     * @param auth the caller's access token
     * @param request the create request
     * @return the stored manifest
     */
    CompletableFuture<VaultManifest> createRepo(AuthToken auth, CreateRepoRequest request);

    /**
     * Pulls the current manifest and payload for a repository.
     *
     * @param auth the caller's access token
     * @param request the pull request
     * @return the pull result
     */
    CompletableFuture<PullResponse> pull(AuthToken auth, PullRequest request);

    /**
     * Pushes a new payload using optimistic concurrency.
     *
     * @param auth the caller's access token
     * @param request the push request
     * @return the push result (accepted, or a conflict to retry)
     */
    CompletableFuture<PushResponse> push(AuthToken auth, PushRequest request);

    /**
     * Adds a member to a repository.
     *
     * @param auth the caller's access token
     * @param request the member-add request
     * @return the updated manifest
     */
    CompletableFuture<VaultManifest> addMember(AuthToken auth, MemberAddRequest request);

    /**
     * Removes a member, atomically applying the accompanying key rotation.
     *
     * @param auth the caller's access token
     * @param request the member-remove request
     * @return the updated manifest
     */
    CompletableFuture<VaultManifest> removeMember(AuthToken auth, MemberRemoveRequest request);

    /**
     * Fetches a single member's public-key entry, for wrapping a data key to a prospective member.
     *
     * @param auth the caller's access token
     * @param repoId the repository
     * @param memberId the Ed25519 id of the member to look up
     * @return the member entry
     */
    CompletableFuture<MemberEntry> fetchMemberKey(AuthToken auth, String repoId, String memberId);
}
