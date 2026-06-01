package lol.trq.alts.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.junit.jupiter.api.Test;

/** Ed25519 signing/verification and raw 32-byte key encoding round-trips. */
class Ed25519Test {

    private static final byte[] MESSAGE = "challenge-nonce".getBytes(StandardCharsets.UTF_8);

    @Test
    void signThenVerifySucceeds() throws Exception {
        KeyPair kp = Ed25519.newKeyPair();
        byte[] sig = Ed25519.sign(kp.getPrivate(), MESSAGE);
        assertTrue(Ed25519.verify(kp.getPublic(), MESSAGE, sig));
    }

    @Test
    void verifyFailsForWrongKeyOrMessage() throws Exception {
        KeyPair kp = Ed25519.newKeyPair();
        KeyPair other = Ed25519.newKeyPair();
        byte[] sig = Ed25519.sign(kp.getPrivate(), MESSAGE);

        assertFalse(Ed25519.verify(other.getPublic(), MESSAGE, sig));
        assertFalse(Ed25519.verify(kp.getPublic(), "tampered".getBytes(StandardCharsets.UTF_8), sig));
    }

    @Test
    void publicKeyEncodeDecodeRoundTrips() throws Exception {
        KeyPair kp = Ed25519.newKeyPair();
        byte[] raw = Ed25519.encodePublicKey(kp.getPublic());
        assertEquals(32, raw.length);

        PublicKey decoded = Ed25519.decodePublicKey(raw);
        assertArrayEquals(raw, Ed25519.encodePublicKey(decoded));

        // The decoded public key verifies a signature made by the original private key.
        byte[] sig = Ed25519.sign(kp.getPrivate(), MESSAGE);
        assertTrue(Ed25519.verify(decoded, MESSAGE, sig));
    }

    @Test
    void privateKeyEncodeDecodeRoundTrips() throws Exception {
        KeyPair kp = Ed25519.newKeyPair();
        byte[] seed = Ed25519.encodePrivateKey(kp.getPrivate());
        assertEquals(32, seed.length);

        PrivateKey decoded = Ed25519.decodePrivateKey(seed);
        byte[] sig = Ed25519.sign(decoded, MESSAGE);
        assertTrue(Ed25519.verify(kp.getPublic(), MESSAGE, sig));
    }
}
