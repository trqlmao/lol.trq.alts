package lol.trq.alts.store;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM encryption and decryption for secure local data persistence. Uses PBKDF2 for key derivation
 * and GCM for authenticated encryption, so stored account data is both private and tamper-evident.
 *
 * @author trq
 * @since 0.1.0
 */
public final class EncryptionUtil {

    /** The length of the initialization vector for AES-GCM, in bytes. */
    private static final int GCM_IV_LENGTH = 12;

    /** The size of the authentication tag, in bits. */
    private static final int GCM_TAG_LENGTH = 128;

    private EncryptionUtil() {}

    /**
     * Generates a unique, environment-specific string used as a password for key derivation. This
     * binds the encrypted data to the specific hardware and user profile of the system.
     *
     * @param appBinding a host-supplied constant mixed into the key so files are bound to one
     *     application; changing it for an existing store renders prior files undecryptable
     * @return a hardware-bound string identifier
     */
    public static String getHardwareKey(String appBinding) {
        return System.getProperty("user.name")
                + System.getProperty("os.name")
                + System.getProperty("user.home")
                + appBinding;
    }

    /**
     * Encrypts a plaintext string using AES-GCM with a randomly generated salt and IV. The result is a
     * Base64-encoded bundle containing {@code [salt][iv][ciphertext]}.
     *
     * @param plaintext the string to encrypt
     * @param password the password or hardware key used for derivation
     * @return a Base64-encoded string containing the encrypted data and metadata
     * @throws Exception if encryption or key derivation fails
     */
    public static String encrypt(String plaintext, String password) throws Exception {
        SecureRandom random = new SecureRandom();

        // Generate a fresh salt for PBKDF2
        byte[] salt = new byte[16];
        random.nextBytes(salt);

        // Generate a fresh IV for GCM
        byte[] iv = new byte[GCM_IV_LENGTH];
        random.nextBytes(iv);

        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // Bundle metadata (salt + IV) with the ciphertext for portability
        byte[] combined = new byte[salt.length + iv.length + ciphertext.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(iv, 0, combined, salt.length, iv.length);
        System.arraycopy(ciphertext, 0, combined, salt.length + iv.length, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * Decrypts a Base64-encoded bundle previously produced by {@link #encrypt(String, String)}.
     * Extracts the salt and IV from the bundle to recreate the decryption key.
     *
     * @param encrypted the Base64-encoded bundle
     * @param password the password or hardware key used for derivation
     * @return the original decrypted plaintext string
     * @throws Exception if decryption fails, indicating an incorrect key or tampered data
     */
    public static String decrypt(String encrypted, String password) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encrypted);

        byte[] salt = new byte[16];
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - salt.length - iv.length];

        // Extract metadata and ciphertext from the bundle
        System.arraycopy(combined, 0, salt, 0, salt.length);
        System.arraycopy(combined, salt.length, iv, 0, iv.length);
        System.arraycopy(combined, salt.length + iv.length, ciphertext, 0, ciphertext.length);

        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    /**
     * Derives a 256-bit AES key from a password and salt using PBKDF2WithHmacSHA256.
     *
     * @param password the input password string
     * @param salt the unique salt for this operation
     * @return a secret key suitable for AES encryption
     * @throws Exception if key factory generation fails
     */
    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        // 65,536 iterations balances security and performance for a hardware-bound local file
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }
}
