package lol.trq.alts.vault.federation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lol.trq.alts.crypto.VaultIdentity;
import lol.trq.alts.crypto.X25519;
import lol.trq.alts.crypto.X25519HkdfAesGcmKeyWrap;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.spi.VaultTransportResolver;
import lol.trq.alts.vault.MemberPublicKey;
import lol.trq.alts.vault.RepoContext;
import lol.trq.alts.vault.SharedVault;
import lol.trq.alts.vault.transport.AuthChallenge;
import lol.trq.alts.vault.transport.AuthToken;
import lol.trq.alts.vault.transport.ChallengeRequest;
import lol.trq.alts.vault.transport.CreateRepoRequest;
import lol.trq.alts.vault.transport.MemberAddRequest;
import lol.trq.alts.vault.transport.MemberEntry;
import lol.trq.alts.vault.transport.MemberRemoveRequest;
import lol.trq.alts.vault.transport.PullRequest;
import lol.trq.alts.vault.transport.PullResponse;
import lol.trq.alts.vault.transport.PushRequest;
import lol.trq.alts.vault.transport.PushResponse;
import lol.trq.alts.vault.transport.TokenRequest;
import lol.trq.alts.vault.transport.VaultManifest;
import lol.trq.alts.vault.transport.VaultTransport;
import org.junit.jupiter.api.Test;

/**
 * Proves the federated client-redirect path: a member normally pointed at one server joins a repository
 * hosted on another, reachable purely by resolving a transport for the repository's home host. The join
 * handshake itself is transport-free crypto + addressing, so no live server is needed.
 */
class FederationJoinTest {

    private final SharedVault vault = new SharedVault(new X25519HkdfAesGcmKeyWrap());

    private static MemberPublicKey pub(VaultIdentity id) throws Exception {
        return new MemberPublicKey(
                id.identityId(),
                Base64.getEncoder().encodeToString(X25519.encodePublicKey(id.keyAgreementPublicKey())));
    }

    @Test
    void memberJoinsRepoHostedOnAnotherServer() throws Exception {
        VaultIdentity alice = VaultIdentity.create("alice".toCharArray());
        VaultIdentity bob = VaultIdentity.create("bob".toCharArray());

        // Alice, on one server, creates a repo holding an alt with provenance set.
        AltAccount alt =
                new AltAccount("u", "Shared", "tok", AccountType.MICROSOFT, 1000L, null, null, "democlient", "user1");
        SharedVault.CreatedRepo created = vault.createRepo(alice, List.of(alt));
        RepoAddress repoAddress =
                new RepoAddress("vault.example", created.manifest().repoId());

        // Bob publishes his keys as an invite token; Alice accepts it and wraps the data key to Bob.
        String inviteToken = InviteRequest.forMember(pub(bob)).encode();
        MemberPublicKey joiner = InviteRequest.decode(inviteToken).toMemberPublicKey();
        MemberEntry bobEntry = vault.addMember(created.context(), joiner);
        VaultManifest manifest = new VaultManifest(
                created.manifest().repoId(),
                created.manifest().schemeId(),
                created.manifest().keyEpoch(),
                created.manifest().payloadVersion(),
                List.of(created.manifest().members().get(0), bobEntry));

        // Alice replies with a locator; Bob decodes where the repo lives.
        String locatorToken = RepoLocator.of(
                        repoAddress,
                        created.manifest().schemeId(),
                        created.manifest().keyEpoch(),
                        null)
                .encode();
        RepoAddress resolved = RepoLocator.decode(locatorToken).toRepoAddress();
        assertEquals("vault.example", resolved.host());

        // Bob is normally pointed at "other.example"; his resolver still dials the repo's home host.
        VaultTransport homeTransport = new ThrowingTransport();
        VaultTransport otherTransport = new ThrowingTransport();
        VaultTransportResolver resolver =
                host -> Map.of("vault.example", homeTransport, "other.example", otherTransport)
                        .get(host);
        assertSame(homeTransport, resolver.transportFor(resolved.host()));
        assertNull(resolver.transportFor("unknown.example"));

        // Bob opens the cross-server repo with his own identity and reads the payload, provenance intact.
        RepoContext bobCtx = vault.openRepo(manifest, bob);
        AltAccount recovered =
                vault.decryptPayload(bobCtx, created.envelope(), 0).get(0);
        assertEquals("democlient", recovered.sourceClient());
        assertEquals("user1", recovered.sourceUser());
    }

    /** A VaultTransport that exists only as a routing target; calling any RPC is a test bug. */
    private static final class ThrowingTransport implements VaultTransport {
        @Override
        public CompletableFuture<AuthChallenge> challenge(ChallengeRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AuthToken> token(TokenRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<VaultManifest> createRepo(AuthToken auth, CreateRepoRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<PullResponse> pull(AuthToken auth, PullRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<PushResponse> push(AuthToken auth, PushRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<VaultManifest> addMember(AuthToken auth, MemberAddRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<VaultManifest> removeMember(AuthToken auth, MemberRemoveRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<MemberEntry> fetchMemberKey(AuthToken auth, String repoId, String memberId) {
            throw new UnsupportedOperationException();
        }
    }
}
