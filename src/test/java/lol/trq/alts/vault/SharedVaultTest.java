package lol.trq.alts.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import lol.trq.alts.crypto.VaultIdentity;
import lol.trq.alts.crypto.X25519;
import lol.trq.alts.crypto.X25519HkdfAesGcmKeyWrap;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.BanInfo;
import lol.trq.alts.vault.transport.EncryptedEnvelope;
import lol.trq.alts.vault.transport.MemberEntry;
import lol.trq.alts.vault.transport.VaultManifest;
import org.junit.jupiter.api.Test;

/** End-to-end shared-vault crypto: membership, sharing, and rotation-on-removal. */
class SharedVaultTest {

    private final SharedVault vault = new SharedVault(new X25519HkdfAesGcmKeyWrap());

    private static MemberPublicKey pub(VaultIdentity id) throws Exception {
        return new MemberPublicKey(
                id.identityId(),
                Base64.getEncoder().encodeToString(X25519.encodePublicKey(id.keyAgreementPublicKey())));
    }

    private static List<AltAccount> alts(String name) {
        // Fixed lastUsed so value-equality round-trips are deterministic.
        return List.of(new AltAccount(
                "uuid-" + name, name, "token-" + name, AccountType.MICROSOFT, 1000L, null, null, null, null));
    }

    @Test
    void createAddEncryptThenOtherMemberDecrypts() throws Exception {
        VaultIdentity alice = VaultIdentity.create("alice-pw".toCharArray());
        VaultIdentity bob = VaultIdentity.create("bob-pw".toCharArray());

        SharedVault.CreatedRepo created = vault.createRepo(alice, alts("shared"));

        // Alice invites Bob: wrap the data key to Bob, then publish a two-member manifest.
        MemberEntry bobEntry = vault.addMember(created.context(), pub(bob));
        VaultManifest manifest = new VaultManifest(
                created.manifest().repoId(),
                created.manifest().schemeId(),
                created.manifest().keyEpoch(),
                created.manifest().payloadVersion(),
                List.of(created.manifest().members().get(0), bobEntry));

        // Bob opens the repo and decrypts the payload Alice wrote.
        RepoContext bobCtx = vault.openRepo(manifest, bob);
        List<AltAccount> recovered = vault.decryptPayload(bobCtx, created.envelope(), 0);

        assertEquals(alts("shared"), recovered);
    }

    @Test
    void rotateOnRemovePreventsRemovedMemberDecrypt() throws Exception {
        VaultIdentity alice = VaultIdentity.create("a".toCharArray());
        VaultIdentity bob = VaultIdentity.create("b".toCharArray());
        VaultIdentity carol = VaultIdentity.create("c".toCharArray());

        SharedVault.CreatedRepo created = vault.createRepo(alice, alts("v1"));
        MemberEntry bobEntry = vault.addMember(created.context(), pub(bob));
        MemberEntry carolEntry = vault.addMember(created.context(), pub(carol));

        // Remove Carol: rotate to {alice, bob} only.
        SharedVault.RotationResult rotation =
                vault.removeMember(created.context(), alts("v2"), List.of(pub(alice), pub(bob)));
        VaultManifest rotated = new VaultManifest(
                created.manifest().repoId(),
                created.manifest().schemeId(),
                rotation.newKeyEpoch(),
                rotation.context().payloadVersion(),
                rotation.rewrappedMembers());

        // Bob still reads the rotated payload.
        RepoContext bobCtx = vault.openRepo(rotated, bob);
        assertEquals(alts("v2"), vault.decryptPayload(bobCtx, rotation.envelope(), 0));

        // Carol is no longer a member of the rotated manifest and cannot open it.
        assertThrows(VaultException.class, () -> vault.openRepo(rotated, carol));

        // Even with her stale entry, Carol's old key cannot decrypt the new-epoch envelope.
        VaultManifest carolStillListed = new VaultManifest(
                rotated.repoId(), rotated.schemeId(), 0, created.manifest().payloadVersion(), List.of(carolEntry));
        RepoContext carolOldCtx = vault.openRepo(carolStillListed, carol);
        assertThrows(Exception.class, () -> vault.decryptPayload(carolOldCtx, rotation.envelope(), 0));
        // sanity: bobEntry was a real entry under the original epoch
        assertEquals(0L, bobEntry.keyEpoch());
    }

    @Test
    void sharesLastUsedActivityAndObservedBan() throws Exception {
        VaultIdentity alice = VaultIdentity.create("a".toCharArray());
        AltAccount banned = new AltAccount(
                "u-banned",
                "BannedAlt",
                "tok",
                AccountType.MICROSOFT,
                5000L,
                "member-alice",
                Map.of("hypixel", new BanInfo(true, 9000L, "self", "Watchdog", "member-bob")),
                null,
                null);

        SharedVault.CreatedRepo created = vault.createRepo(alice, List.of(banned));
        AltAccount recovered =
                vault.decryptPayload(created.context(), created.envelope(), 0).get(0);

        assertEquals(5000L, recovered.lastUsed()); // activity shared
        assertEquals("member-alice", recovered.lastUsedBy()); // activity attribution shared
        assertTrue(recovered.banned()); // ban status shared
        assertTrue(recovered.banned("hypixel")); // per-server ban shared
        assertEquals("Watchdog", recovered.bans().get("hypixel").detail());
        assertEquals(9000L, recovered.bans().get("hypixel").observedAt());
        assertEquals("member-bob", recovered.bans().get("hypixel").observedBy()); // ban attribution shared
    }

    @Test
    void sharesProvenanceFields() throws Exception {
        VaultIdentity alice = VaultIdentity.create("a".toCharArray());
        AltAccount sampleAlt = new AltAccount(
                "u-prov", "ProvAlt", "tok", AccountType.MICROSOFT, 1000L, null, null, "democlient", "user1");

        SharedVault.CreatedRepo created = vault.createRepo(alice, List.of(sampleAlt));
        AltAccount recovered =
                vault.decryptPayload(created.context(), created.envelope(), 0).get(0);

        // Provenance rides inside the encrypted payload and survives the round-trip to another member.
        assertEquals("democlient", recovered.sourceClient());
        assertEquals("user1", recovered.sourceUser());
    }

    @Test
    void payloadVersionAdvancesOnEncrypt() throws Exception {
        VaultIdentity alice = VaultIdentity.create("a".toCharArray());
        SharedVault.CreatedRepo created = vault.createRepo(alice, alts("v1"));
        assertEquals(1L, created.envelope().payloadVersion());

        EncryptedEnvelope next = vault.encryptPayload(created.context(), alts("v2"));
        assertEquals(2L, next.payloadVersion());
    }

    @Test
    void rejectsRolledBackPayload() throws Exception {
        VaultIdentity alice = VaultIdentity.create("a".toCharArray());
        SharedVault.CreatedRepo created = vault.createRepo(alice, alts("v1"));
        assertThrows(VaultException.class, () -> vault.decryptPayload(created.context(), created.envelope(), 5));
    }

    @Test
    void selfRoundTripOpensAndReads() throws Exception {
        VaultIdentity alice = VaultIdentity.create("a".toCharArray());
        SharedVault.CreatedRepo created = vault.createRepo(alice, alts("solo"));
        RepoContext reopened = vault.openRepo(created.manifest(), alice);
        assertTrue(reopened.dataKey().secretKey() != null);
        assertEquals(alts("solo"), vault.decryptPayload(reopened, created.envelope(), 0));
    }
}
