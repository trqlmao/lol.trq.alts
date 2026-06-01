package lol.trq.alts.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Default key-wrap scheme: ephemeral X25519 ECDH → HKDF-SHA256 → AES-256-GCM. For each wrap a fresh
 * ephemeral key pair is generated; the shared secret with the recipient is run through HKDF (salted
 * with the ephemeral public key) to a key-encryption key, which GCM-encrypts the repository data key.
 * Only the recipient's private key can reproduce the shared secret and unwrap.
 *
 * @author trq
 * @since 0.2.0
 */
public final class X25519HkdfAesGcmKeyWrap implements KeyWrapScheme {

    /** The stable, versioned identifier for this scheme. */
    public static final String SCHEME_ID = "X25519-HKDF-SHA256-AESGCM-v1";

    private static final byte[] HKDF_INFO = "lol.trq.alts/rdk-wrap/v1".getBytes(StandardCharsets.UTF_8);
    private static final int KEK_BYTES = 32;

    @Override
    public String schemeId() {
        return SCHEME_ID;
    }

    @Override
    public WrappedKey wrap(SecretKey dataKey, PublicKey recipientPublicKey) throws CryptoException {
        KeyPair ephemeral = X25519.newKeyPair();
        byte[] ephemeralRaw = X25519.encodePublicKey(ephemeral.getPublic());
        byte[] sharedSecret = X25519.agree(ephemeral.getPrivate(), recipientPublicKey);
        SecretKey kek = Hkdf.deriveKey(sharedSecret, ephemeralRaw, HKDF_INFO, KEK_BYTES);
        byte[] iv = PayloadCipher.newIv();
        byte[] ciphertext = PayloadCipher.encrypt(kek, iv, HKDF_INFO, dataKey.getEncoded());
        Base64.Encoder b64 = Base64.getEncoder();
        return new WrappedKey(
                SCHEME_ID, b64.encodeToString(ephemeralRaw), b64.encodeToString(iv), b64.encodeToString(ciphertext));
    }

    @Override
    public SecretKey unwrap(WrappedKey blob, PrivateKey recipientPrivateKey) throws CryptoException {
        if (!SCHEME_ID.equals(blob.schemeId())) {
            throw new CryptoException("unknown key-wrap scheme: " + blob.schemeId());
        }
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] ephemeralRaw = b64.decode(blob.ephemeralPublicKey());
        byte[] iv = b64.decode(blob.iv());
        byte[] ciphertext = b64.decode(blob.ciphertext());
        PublicKey ephemeralPublic = X25519.decodePublicKey(ephemeralRaw);
        byte[] sharedSecret = X25519.agree(recipientPrivateKey, ephemeralPublic);
        SecretKey kek = Hkdf.deriveKey(sharedSecret, ephemeralRaw, HKDF_INFO, KEK_BYTES);
        byte[] dataKeyBytes = PayloadCipher.decrypt(kek, iv, HKDF_INFO, ciphertext);
        return new SecretKeySpec(dataKeyBytes, "AES");
    }
}
