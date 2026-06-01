package lol.trq.alts.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Create / unlock / recovery round-trips for the vault identity. */
class VaultIdentityTest {

    @Test
    void createThenUnlockWithSamePassphrase() throws Exception {
        VaultIdentity created = VaultIdentity.create("correct horse".toCharArray());
        WrappedIdentity stored = created.export();

        VaultIdentity unlocked = VaultIdentity.unlock(stored, "correct horse".toCharArray());

        assertEquals(created.identityId(), unlocked.identityId());

        // The unlocked private key still signs verifiably against the original public identity.
        byte[] nonce = "server-nonce".getBytes(StandardCharsets.UTF_8);
        byte[] sig = unlocked.signChallenge(nonce);
        assertTrue(Ed25519.verify(created.identityPublicKey(), nonce, sig));

        // The unlocked X25519 key unwraps a data key wrapped to the original public key.
        KeyWrapScheme scheme = new X25519HkdfAesGcmKeyWrap();
        RepoDataKey rdk = RepoDataKey.generate();
        WrappedKey blob = rdk.wrapTo(scheme, created.keyAgreementPublicKey());
        RepoDataKey recovered = RepoDataKey.unwrap(scheme, blob, unlocked.keyAgreementPrivateKey(), 0);
        assertArrayEquals(rdk.secretKey().getEncoded(), recovered.secretKey().getEncoded());
    }

    @Test
    void unlockFailsWithWrongPassphrase() throws Exception {
        WrappedIdentity stored = VaultIdentity.create("right".toCharArray()).export();
        assertThrows(CryptoException.class, () -> VaultIdentity.unlock(stored, "wrong".toCharArray()));
    }

    @Test
    void identityIdMatchesStoredPublicKey() throws Exception {
        VaultIdentity id = VaultIdentity.create("pw".toCharArray());
        assertEquals(id.export().ed25519PublicKey(), id.identityId());
    }

    @Test
    void recoveryCodeRestoresIdentity() throws Exception {
        VaultIdentity id = VaultIdentity.create("pw".toCharArray());
        String code = RecoveryCode.newCode();
        WrappedIdentity withRecovery = id.withRecovery(code);
        assertTrue(withRecovery.hasRecovery());

        VaultIdentity restored = VaultIdentity.unlockWithRecovery(withRecovery, code);
        assertEquals(id.identityId(), restored.identityId());

        // Recovery preserves the passphrase path too.
        assertEquals(
                id.identityId(),
                VaultIdentity.unlock(withRecovery, "pw".toCharArray()).identityId());
    }

    @Test
    void recoveryFailsWithWrongCodeAndWhenAbsent() throws Exception {
        VaultIdentity id = VaultIdentity.create("pw".toCharArray());
        WrappedIdentity withRecovery = id.withRecovery(RecoveryCode.newCode());
        assertThrows(
                CryptoException.class, () -> VaultIdentity.unlockWithRecovery(withRecovery, RecoveryCode.newCode()));
        // No recovery sealing present.
        assertThrows(CryptoException.class, () -> VaultIdentity.unlockWithRecovery(id.export(), "anything"));
    }

    @Test
    void distinctIdentitiesDiffer() throws Exception {
        assertNotEquals(
                VaultIdentity.create("pw".toCharArray()).identityId(),
                VaultIdentity.create("pw".toCharArray()).identityId());
    }
}
