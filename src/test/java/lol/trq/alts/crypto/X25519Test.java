package lol.trq.alts.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** X25519 ECDH agreement and raw 32-byte key encoding round-trips. */
class X25519Test {

    @Test
    void ecdhAgreementIsSymmetric() throws Exception {
        KeyPair a = X25519.newKeyPair();
        KeyPair b = X25519.newKeyPair();

        byte[] ab = X25519.agree(a.getPrivate(), b.getPublic());
        byte[] ba = X25519.agree(b.getPrivate(), a.getPublic());

        assertArrayEquals(ab, ba);
        assertEquals(32, ab.length);
    }

    @Test
    void publicKeyEncodeDecodeRoundTrips() throws Exception {
        KeyPair kp = X25519.newKeyPair();
        byte[] raw = X25519.encodePublicKey(kp.getPublic());
        assertEquals(32, raw.length);

        PublicKey decoded = X25519.decodePublicKey(raw);
        assertArrayEquals(raw, X25519.encodePublicKey(decoded));

        // A decoded peer key still agrees to the same secret.
        KeyPair other = X25519.newKeyPair();
        assertArrayEquals(X25519.agree(other.getPrivate(), kp.getPublic()), X25519.agree(other.getPrivate(), decoded));
    }

    @Test
    void privateKeyEncodeDecodeRoundTrips() throws Exception {
        KeyPair kp = X25519.newKeyPair();
        byte[] raw = X25519.encodePrivateKey(kp.getPrivate());
        assertEquals(32, raw.length);

        PrivateKey decoded = X25519.decodePrivateKey(raw);
        KeyPair peer = X25519.newKeyPair();
        assertArrayEquals(X25519.agree(kp.getPrivate(), peer.getPublic()), X25519.agree(decoded, peer.getPublic()));
    }

    @Test
    void distinctKeyPairsDeriveDistinctSecrets() throws Exception {
        KeyPair a = X25519.newKeyPair();
        KeyPair b = X25519.newKeyPair();
        KeyPair c = X25519.newKeyPair();
        assertFalse(Arrays.equals(
                X25519.agree(a.getPrivate(), b.getPublic()), X25519.agree(a.getPrivate(), c.getPublic())));
    }
}
