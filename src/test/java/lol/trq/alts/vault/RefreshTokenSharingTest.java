package lol.trq.alts.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import lol.trq.alts.crypto.VaultIdentity;
import lol.trq.alts.crypto.X25519HkdfAesGcmKeyWrap;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.vault.transport.EncryptedEnvelope;
import org.junit.jupiter.api.Test;

class RefreshTokenSharingTest {

    private final SharedVault vault = new SharedVault(new X25519HkdfAesGcmKeyWrap());

    private static AltAccount withRefresh() {
        return AltAccount.of("u", "Alex", "access", AccountType.MICROSOFT).withTokens("access", "secret-refresh", 99L);
    }

    @Test
    void repositoryDefaultsToWithholdingRefreshTokens() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());

        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()));

        assertFalse(repo.manifest().shareRefreshTokens(), "sharing a durable credential must be opt-in");
    }

    @Test
    void refreshTokensAreStrippedFromAWithholdingRepository() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()));

        List<AltAccount> decrypted = vault.decryptPayload(repo.context(), repo.envelope(), 0L);

        assertEquals(1, decrypted.size());
        assertEquals("access", decrypted.get(0).accessToken(), "the access token still travels");
        assertNull(decrypted.get(0).refreshToken(), "the refresh token must not travel");
        assertEquals(0L, decrypted.get(0).expiresAt());
    }

    @Test
    void refreshTokensSurviveInAnOptedInRepository() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()), true);

        List<AltAccount> decrypted = vault.decryptPayload(repo.context(), repo.envelope(), 0L);

        assertTrue(repo.manifest().shareRefreshTokens());
        assertEquals("secret-refresh", decrypted.get(0).refreshToken());
        assertEquals(99L, decrypted.get(0).expiresAt());
    }

    @Test
    void aPeerCannotSmuggleRefreshTokensIntoAWithholdingRepository() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo permissive = vault.createRepo(creator, List.of(withRefresh()), true);

        // Same key material, but the reader's policy withholds. This models a peer running a modified
        // build that writes tokens into a repository whose manifest forbids them.
        RepoContext withholding = new RepoContext(
                permissive.context().repoId(),
                permissive.context().identity(),
                permissive.context().dataKey(),
                permissive.context().payloadVersion(),
                false);
        EncryptedEnvelope smuggled = permissive.envelope();

        List<AltAccount> decrypted = vault.decryptPayload(withholding, smuggled, 0L);

        assertNull(decrypted.get(0).refreshToken(), "the policy must hold regardless of what a peer sends");
    }

    @Test
    void aWithholdingRepositoryNeverWritesTheTokenIntoTheCiphertext() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()), false);
        EncryptedEnvelope pushed = vault.encryptPayload(repo.context(), List.of(withRefresh()));

        // Both envelopes are read back through a permissive context over the same key material, so the
        // read-side strip cannot mask the result. A surviving token would mean the credential was
        // serialized into ciphertext and handed to the server, which the read-side guard cannot undo.
        RepoContext permissiveReader = new RepoContext(
                repo.context().repoId(),
                repo.context().identity(),
                repo.context().dataKey(),
                repo.context().payloadVersion(),
                true);

        assertNull(
                vault.decryptPayload(permissiveReader, repo.envelope(), 0L)
                        .get(0)
                        .refreshToken(),
                "the credential must never enter the initial ciphertext");
        assertNull(
                vault.decryptPayload(permissiveReader, pushed, 0L).get(0).refreshToken(),
                "the credential must never enter a pushed ciphertext");
    }

    @Test
    void rotationPreservesAWithholdingPolicy() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()), false);

        SharedVault.RotationResult rotated = vault.rotateKey(repo.context(), List.of(withRefresh()), List.of());
        List<AltAccount> decrypted = vault.decryptPayload(rotated.context(), rotated.envelope(), 0L);

        assertFalse(rotated.context().shareRefreshTokens());
        assertNull(decrypted.get(0).refreshToken(), "a key rotation must not widen the policy");
    }

    @Test
    void rotationPreservesAnOptedInPolicy() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()), true);

        SharedVault.RotationResult rotated = vault.rotateKey(repo.context(), List.of(withRefresh()), List.of());
        List<AltAccount> decrypted = vault.decryptPayload(rotated.context(), rotated.envelope(), 0L);

        // Regression guard: rotateKey builds a fresh RepoContext. Hardcoding false there would silently
        // downgrade an opted-in repository on every rotation and on every removeMember, and the next
        // encryptPayload would strip the tokens permanently.
        assertTrue(rotated.context().shareRefreshTokens(), "rotation must carry the policy forward");
        assertEquals("secret-refresh", decrypted.get(0).refreshToken());
    }
}
