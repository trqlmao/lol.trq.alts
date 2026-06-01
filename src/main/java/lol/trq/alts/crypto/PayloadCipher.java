package lol.trq.alts.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Authenticated encryption core: AES-256-GCM over a raw {@link SecretKey} with caller-supplied
 * additional authenticated data (AAD). The AAD binds a ciphertext to its repository, payload version,
 * and key epoch so a server (or attacker) cannot splice a blob from one context into another or roll a
 * client back to a stale payload — any mismatch fails the GCM tag on decrypt.
 *
 * <p>Unlike {@code EncryptionUtil}, this class takes an already-derived key and does no password
 * stretching; it is the shared primitive under both the local stores and the shared-vault key
 * hierarchy.
 *
 * @author trq
 * @since 0.2.0
 */
public final class PayloadCipher {

    /** GCM initialization-vector length, in bytes. */
    public static final int IV_BYTES = 12;

    /** GCM authentication-tag length, in bits. */
    public static final int TAG_BITS = 128;

    /** Field separator between the variable-length repo id and the fixed numeric suffix in the AAD. */
    private static final byte AAD_SEPARATOR = 0x1F;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PayloadCipher() {}

    /**
     * Encrypts {@code plaintext} under {@code key} and {@code iv}, authenticating {@code aad}.
     *
     * @param key the AES key
     * @param iv a fresh {@link #IV_BYTES}-byte IV (never reuse one with the same key)
     * @param aad the additional authenticated data, or null/empty for none
     * @param plaintext the bytes to encrypt
     * @return the ciphertext with the GCM tag appended
     * @throws CryptoException if encryption fails
     */
    public static byte[] encrypt(SecretKey key, byte[] iv, byte[] aad, byte[] plaintext) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new CryptoException("AES-GCM encrypt failed", e);
        }
    }

    /**
     * Decrypts {@code ciphertext} under {@code key} and {@code iv}, verifying {@code aad}.
     *
     * @param key the AES key
     * @param iv the {@link #IV_BYTES}-byte IV used at encryption time
     * @param aad the additional authenticated data that must match the encrypt-time value
     * @param ciphertext the ciphertext with the appended GCM tag
     * @return the recovered plaintext
     * @throws CryptoException if the tag is invalid (wrong key, tampered data, or mismatched AAD)
     */
    public static byte[] decrypt(SecretKey key, byte[] iv, byte[] aad, byte[] ciphertext) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new CryptoException("AES-GCM decrypt failed (wrong key, tampered data, or AAD mismatch)", e);
        }
    }

    /**
     * Generates a fresh random {@link #IV_BYTES}-byte IV.
     *
     * @return a new random IV
     */
    public static byte[] newIv() {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    /**
     * Builds the AAD binding a payload to its repository, version, and key epoch. Layout:
     * {@code UTF8(repoId) || 0x1F || int64BE(payloadVersion) || int64BE(keyEpoch)}.
     *
     * @param repoId the repository identifier
     * @param payloadVersion the monotonic payload version
     * @param keyEpoch the key-rotation epoch
     * @return the AAD bytes
     */
    public static byte[] aad(String repoId, long payloadVersion, long keyEpoch) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(repoId.getBytes(StandardCharsets.UTF_8));
        out.write(AAD_SEPARATOR);
        out.writeBytes(int64BE(payloadVersion));
        out.writeBytes(int64BE(keyEpoch));
        return out.toByteArray();
    }

    private static byte[] int64BE(long value) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return b;
    }
}
