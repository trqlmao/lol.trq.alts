package lol.trq.alts.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/** X25519 + HKDF + AES-GCM key-wrap: only the right recipient unwraps. */
class KeyWrapSchemeTest {

    private static SecretKey aesKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return kg.generateKey();
    }

    @Test
    void wrapThenUnwrapRecoversDataKeyForRightRecipient() throws Exception {
        KeyWrapScheme scheme = new X25519HkdfAesGcmKeyWrap();
        KeyPair recipient = X25519.newKeyPair();
        SecretKey dataKey = aesKey();

        WrappedKey blob = scheme.wrap(dataKey, recipient.getPublic());
        SecretKey unwrapped = scheme.unwrap(blob, recipient.getPrivate());

        assertArrayEquals(dataKey.getEncoded(), unwrapped.getEncoded());
        assertEquals(X25519HkdfAesGcmKeyWrap.SCHEME_ID, blob.schemeId());
    }

    @Test
    void unwrapFailsForWrongRecipient() throws Exception {
        KeyWrapScheme scheme = new X25519HkdfAesGcmKeyWrap();
        KeyPair recipient = X25519.newKeyPair();
        KeyPair stranger = X25519.newKeyPair();
        WrappedKey blob = scheme.wrap(aesKey(), recipient.getPublic());

        assertThrows(CryptoException.class, () -> scheme.unwrap(blob, stranger.getPrivate()));
    }

    @Test
    void unwrapRejectsUnknownSchemeId() throws Exception {
        KeyWrapScheme scheme = new X25519HkdfAesGcmKeyWrap();
        KeyPair recipient = X25519.newKeyPair();
        WrappedKey blob = scheme.wrap(aesKey(), recipient.getPublic());
        WrappedKey forged = new WrappedKey("bogus-v9", blob.ephemeralPublicKey(), blob.iv(), blob.ciphertext());

        assertThrows(CryptoException.class, () -> scheme.unwrap(forged, recipient.getPrivate()));
    }
}
