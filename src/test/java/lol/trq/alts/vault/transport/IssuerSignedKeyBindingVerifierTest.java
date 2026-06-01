package lol.trq.alts.vault.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.util.Base64;
import lol.trq.alts.crypto.Ed25519;
import lol.trq.alts.crypto.WrappedKey;
import lol.trq.alts.vault.MemberPublicKey;
import lol.trq.alts.vault.VaultException;
import org.junit.jupiter.api.Test;

class IssuerSignedKeyBindingVerifierTest {

    private static final String ED = "member-ed25519-b64";
    private static final String X = "member-x25519-b64";

    private static WrappedKey wrappedKey() {
        return new WrappedKey("X25519-HKDF-SHA256-AESGCM-v1", "ZXBo", "aXY=", "Y3Q=");
    }

    @Test
    void acceptsBindingSignedByTheTrustedIssuer() throws Exception {
        KeyPair issuer = Ed25519.newKeyPair();
        byte[] message = IssuerSignedKeyBindingVerifier.bindingMessage(ED, X);
        String sig = Base64.getEncoder().encodeToString(Ed25519.sign(issuer.getPrivate(), message));

        KeyBindingVerifier verifier = new IssuerSignedKeyBindingVerifier(issuer.getPublic());
        MemberEntry entry = new MemberEntry(ED, X, wrappedKey(), 0L, sig);

        MemberPublicKey verified = verifier.verify("repo-1", entry);
        assertEquals(new MemberPublicKey(ED, X), verified);
    }

    @Test
    void rejectsBindingFromAnotherIssuer() throws Exception {
        KeyPair realIssuer = Ed25519.newKeyPair();
        KeyPair attacker = Ed25519.newKeyPair();
        byte[] message = IssuerSignedKeyBindingVerifier.bindingMessage(ED, X);
        String sig = Base64.getEncoder().encodeToString(Ed25519.sign(attacker.getPrivate(), message));

        KeyBindingVerifier verifier = new IssuerSignedKeyBindingVerifier(realIssuer.getPublic());
        MemberEntry entry = new MemberEntry(ED, X, wrappedKey(), 0L, sig);

        assertThrows(VaultException.class, () -> verifier.verify("repo-1", entry));
    }

    @Test
    void rejectsTamperedKeys() throws Exception {
        KeyPair issuer = Ed25519.newKeyPair();
        // Issuer signs the genuine binding, but the served entry swaps the X25519 key.
        byte[] message = IssuerSignedKeyBindingVerifier.bindingMessage(ED, X);
        String sig = Base64.getEncoder().encodeToString(Ed25519.sign(issuer.getPrivate(), message));

        KeyBindingVerifier verifier = new IssuerSignedKeyBindingVerifier(issuer.getPublic());
        MemberEntry tampered = new MemberEntry(ED, "attacker-x25519-b64", wrappedKey(), 0L, sig);

        assertThrows(VaultException.class, () -> verifier.verify("repo-1", tampered));
    }

    @Test
    void rejectsMissingSignature() throws Exception {
        KeyPair issuer = Ed25519.newKeyPair();
        KeyBindingVerifier verifier = new IssuerSignedKeyBindingVerifier(issuer.getPublic());
        MemberEntry entry = new MemberEntry(ED, X, wrappedKey(), 0L);

        assertThrows(VaultException.class, () -> verifier.verify("repo-1", entry));
    }
}
