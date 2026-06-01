package lol.trq.alts.crypto;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import javax.crypto.KeyAgreement;

/**
 * X25519 (Curve25519 ECDH) key generation, agreement, and raw 32-byte key encoding via the JDK's
 * built-in {@code XDH} provider. Raw little-endian 32-byte encodings (RFC 7748) are used on the wire
 * so member public keys are compact and provider-portable.
 *
 * @author trq
 * @since 0.2.0
 */
public final class X25519 {

    /** Raw key length, in bytes. */
    public static final int KEY_BYTES = 32;

    private X25519() {}

    /**
     * Generates a fresh X25519 key pair.
     *
     * @return a new key pair
     * @throws CryptoException if key generation fails
     */
    public static KeyPair newKeyPair() throws CryptoException {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (Exception e) {
            throw new CryptoException("X25519 keygen failed", e);
        }
    }

    /**
     * Performs ECDH, producing the raw 32-byte shared secret.
     *
     * @param ownPrivate this party's private key
     * @param peerPublic the peer's public key
     * @return the 32-byte shared secret
     * @throws CryptoException if agreement fails
     */
    public static byte[] agree(PrivateKey ownPrivate, PublicKey peerPublic) throws CryptoException {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(ownPrivate);
            ka.doPhase(peerPublic, true);
            return ka.generateSecret();
        } catch (Exception e) {
            throw new CryptoException("X25519 agreement failed", e);
        }
    }

    /**
     * Encodes a public key to its raw 32-byte little-endian u-coordinate.
     *
     * @param publicKey the X25519 public key
     * @return the raw 32-byte encoding
     * @throws CryptoException if the key is not an X25519 key
     */
    public static byte[] encodePublicKey(PublicKey publicKey) throws CryptoException {
        if (!(publicKey instanceof XECPublicKey xec)) {
            throw new CryptoException("not an X25519 public key: " + publicKey.getClass());
        }
        return bigIntToLe(xec.getU(), KEY_BYTES);
    }

    /**
     * Decodes a raw 32-byte little-endian u-coordinate into a public key.
     *
     * @param raw the raw 32-byte encoding
     * @return the X25519 public key
     * @throws CryptoException if decoding fails
     */
    public static PublicKey decodePublicKey(byte[] raw) throws CryptoException {
        requireLen(raw, "public key");
        try {
            BigInteger u = leToBigInt(raw);
            KeyFactory kf = KeyFactory.getInstance("XDH");
            return kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, u));
        } catch (Exception e) {
            throw new CryptoException("X25519 public key decode failed", e);
        }
    }

    /**
     * Encodes a private key to its raw 32-byte scalar.
     *
     * @param privateKey the X25519 private key
     * @return the raw 32-byte scalar
     * @throws CryptoException if the scalar is unavailable
     */
    public static byte[] encodePrivateKey(PrivateKey privateKey) throws CryptoException {
        if (!(privateKey instanceof XECPrivateKey xec)) {
            throw new CryptoException("not an X25519 private key: " + privateKey.getClass());
        }
        return xec.getScalar().orElseThrow(() -> new CryptoException("X25519 scalar not extractable"));
    }

    /**
     * Decodes a raw 32-byte scalar into a private key.
     *
     * @param raw the raw 32-byte scalar
     * @return the X25519 private key
     * @throws CryptoException if decoding fails
     */
    public static PrivateKey decodePrivateKey(byte[] raw) throws CryptoException {
        requireLen(raw, "private key");
        try {
            KeyFactory kf = KeyFactory.getInstance("XDH");
            return kf.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, raw.clone()));
        } catch (Exception e) {
            throw new CryptoException("X25519 private key decode failed", e);
        }
    }

    private static void requireLen(byte[] raw, String what) throws CryptoException {
        if (raw == null || raw.length != KEY_BYTES) {
            throw new CryptoException("X25519 " + what + " must be " + KEY_BYTES + " bytes");
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
        return new BigInteger(1, be);
    }
}
