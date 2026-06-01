package lol.trq.alts.crypto;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;

/**
 * Ed25519 signing, verification, and raw 32-byte key encoding (RFC 8032) via the JDK's built-in
 * {@code Ed25519} provider. The Ed25519 public key is a vault member's stable identity: it is what the
 * server keys membership on and what other members verify signatures against. Distinct from the
 * member's {@link X25519} key, which is only used to wrap the repository data key.
 *
 * @author trq
 * @since 0.2.0
 */
public final class Ed25519 {

    /** Raw public/private key length, in bytes. */
    public static final int KEY_BYTES = 32;

    private Ed25519() {}

    /**
     * Generates a fresh Ed25519 key pair.
     *
     * @return a new key pair
     * @throws CryptoException if key generation fails
     */
    public static KeyPair newKeyPair() throws CryptoException {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new CryptoException("Ed25519 keygen failed", e);
        }
    }

    /**
     * Signs a message.
     *
     * @param privateKey the signing key
     * @param message the message bytes
     * @return the 64-byte signature
     * @throws CryptoException if signing fails
     */
    public static byte[] sign(PrivateKey privateKey, byte[] message) throws CryptoException {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(message);
            return sig.sign();
        } catch (Exception e) {
            throw new CryptoException("Ed25519 sign failed", e);
        }
    }

    /**
     * Verifies a signature.
     *
     * @param publicKey the verification key
     * @param message the message bytes
     * @param signature the signature to verify
     * @return true if the signature is valid for the message and key
     */
    public static boolean verify(PublicKey publicKey, byte[] message, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Encodes a public key to its raw 32-byte RFC 8032 form (little-endian y with the x sign bit in the
     * top bit of the last byte).
     *
     * @param publicKey the Ed25519 public key
     * @return the raw 32-byte encoding
     * @throws CryptoException if the key is not an Ed25519 key
     */
    public static byte[] encodePublicKey(PublicKey publicKey) throws CryptoException {
        if (!(publicKey instanceof EdECPublicKey ed)) {
            throw new CryptoException("not an Ed25519 public key: " + publicKey.getClass());
        }
        EdECPoint point = ed.getPoint();
        byte[] raw = bigIntToLe(point.getY(), KEY_BYTES);
        if (point.isXOdd()) {
            raw[KEY_BYTES - 1] |= (byte) 0x80;
        }
        return raw;
    }

    /**
     * Decodes a raw 32-byte RFC 8032 public key.
     *
     * @param raw the raw 32-byte encoding
     * @return the Ed25519 public key
     * @throws CryptoException if decoding fails
     */
    public static PublicKey decodePublicKey(byte[] raw) throws CryptoException {
        requireLen(raw, "public key");
        try {
            byte[] copy = raw.clone();
            boolean xOdd = (copy[KEY_BYTES - 1] & 0x80) != 0;
            copy[KEY_BYTES - 1] &= (byte) 0x7F;
            BigInteger y = leToBigInt(copy);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            return kf.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
        } catch (Exception e) {
            throw new CryptoException("Ed25519 public key decode failed", e);
        }
    }

    /**
     * Encodes a private key to its raw 32-byte seed.
     *
     * @param privateKey the Ed25519 private key
     * @return the raw 32-byte seed
     * @throws CryptoException if the seed is unavailable
     */
    public static byte[] encodePrivateKey(PrivateKey privateKey) throws CryptoException {
        if (!(privateKey instanceof EdECPrivateKey ed)) {
            throw new CryptoException("not an Ed25519 private key: " + privateKey.getClass());
        }
        return ed.getBytes().orElseThrow(() -> new CryptoException("Ed25519 seed not extractable"));
    }

    /**
     * Decodes a raw 32-byte seed into a private key.
     *
     * @param raw the raw 32-byte seed
     * @return the Ed25519 private key
     * @throws CryptoException if decoding fails
     */
    public static PrivateKey decodePrivateKey(byte[] raw) throws CryptoException {
        requireLen(raw, "private key");
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            return kf.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, raw.clone()));
        } catch (Exception e) {
            throw new CryptoException("Ed25519 private key decode failed", e);
        }
    }

    private static void requireLen(byte[] raw, String what) throws CryptoException {
        if (raw == null || raw.length != KEY_BYTES) {
            throw new CryptoException("Ed25519 " + what + " must be " + KEY_BYTES + " bytes");
        }
    }

    private static byte[] bigIntToLe(BigInteger value, int len) {
        byte[] be = value.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < be.length && i < len; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    private static BigInteger leToBigInt(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) {
            be[i] = le[le.length - 1 - i];
        }
        return new BigInteger(1, Arrays.copyOf(be, be.length));
    }
}
