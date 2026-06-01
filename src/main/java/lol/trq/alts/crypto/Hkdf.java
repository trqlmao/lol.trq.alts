package lol.trq.alts.crypto;

import java.io.ByteArrayOutputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869) extract-and-expand key derivation. Used to turn the raw X25519 shared secret
 * into a uniformly-random AES key-wrapping key. Hand-rolled on {@link Mac} so the library stays
 * dependency-free.
 *
 * @author trq
 * @since 0.2.0
 */
public final class Hkdf {

    /** SHA-256 output length, in bytes. */
    public static final int HASH_LEN = 32;

    private static final String HMAC = "HmacSHA256";

    private Hkdf() {}

    /**
     * The RFC 5869 extract step: {@code PRK = HMAC(salt, ikm)}. A null or empty salt is replaced with a
     * string of {@link #HASH_LEN} zero bytes per the spec.
     *
     * @param salt the optional salt
     * @param ikm the input keying material
     * @return the pseudo-random key (32 bytes)
     * @throws CryptoException if the HMAC primitive is unavailable
     */
    public static byte[] extract(byte[] salt, byte[] ikm) throws CryptoException {
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        return hmac(effectiveSalt, ikm);
    }

    /**
     * The RFC 5869 expand step. Produces {@code length} bytes of output keying material.
     *
     * @param prk the pseudo-random key from {@link #extract(byte[], byte[])}
     * @param info optional context/application-specific info
     * @param length the desired output length in bytes (at most {@code 255 * 32})
     * @return the output keying material
     * @throws CryptoException if the length is out of range or the HMAC primitive is unavailable
     */
    public static byte[] expand(byte[] prk, byte[] info, int length) throws CryptoException {
        if (length < 0 || length > 255 * HASH_LEN) {
            throw new CryptoException("HKDF expand length out of range: " + length);
        }
        byte[] safeInfo = info == null ? new byte[0] : info;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        int counter = 1;
        while (out.size() < length) {
            byte[] input = new byte[previous.length + safeInfo.length + 1];
            System.arraycopy(previous, 0, input, 0, previous.length);
            System.arraycopy(safeInfo, 0, input, previous.length, safeInfo.length);
            input[input.length - 1] = (byte) counter;
            previous = hmac(prk, input);
            out.writeBytes(previous);
            counter++;
        }
        return Arrays.copyOf(out.toByteArray(), length);
    }

    /**
     * Convenience full extract-then-expand returning an AES {@link SecretKey}.
     *
     * @param ikm the input keying material (for example an ECDH shared secret)
     * @param salt the optional salt
     * @param info optional context info
     * @param keyBytes the desired key length in bytes (16 or 32)
     * @return an AES secret key derived from {@code ikm}
     * @throws CryptoException if derivation fails
     */
    public static SecretKey deriveKey(byte[] ikm, byte[] salt, byte[] info, int keyBytes) throws CryptoException {
        byte[] prk = extract(salt, ikm);
        byte[] okm = expand(prk, info, keyBytes);
        return new SecretKeySpec(okm, "AES");
    }

    private static byte[] hmac(byte[] key, byte[] data) throws CryptoException {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new CryptoException("HKDF HMAC failed", e);
        }
    }
}
