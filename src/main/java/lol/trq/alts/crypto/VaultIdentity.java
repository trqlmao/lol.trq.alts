package lol.trq.alts.crypto;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;

/**
 * A member's cryptographic identity: an Ed25519 key pair (the stable public identity used to
 * authenticate to the server and to sign key bindings) plus an X25519 key pair (used only to wrap and
 * unwrap repository data keys). Both private keys are held in memory only while unlocked.
 *
 * <p>The two private keys are concatenated into 64 bytes of "private material" and sealed under a
 * passphrase-derived master key for storage as a {@link WrappedIdentity}. There is no account: the
 * Ed25519 public key <em>is</em> the identity. Optionally a second sealing under an offline recovery
 * code can be attached so the identity survives a forgotten passphrase, without any server-side escrow.
 *
 * @author trq
 * @since 0.2.0
 */
public final class VaultIdentity {

    private static final byte[] PASSPHRASE_AAD = "lol.trq.alts/identity-material/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECOVERY_AAD = "lol.trq.alts/identity-recovery/v1".getBytes(StandardCharsets.UTF_8);
    private static final int MATERIAL_BYTES = Ed25519.KEY_BYTES + X25519.KEY_BYTES;

    private final PublicKey edPublic;
    private final PrivateKey edPrivate;
    private final PublicKey xPublic;
    private final PrivateKey xPrivate;
    private final WrappedIdentity stored;

    private VaultIdentity(
            PublicKey edPublic, PrivateKey edPrivate, PublicKey xPublic, PrivateKey xPrivate, WrappedIdentity stored) {
        this.edPublic = edPublic;
        this.edPrivate = edPrivate;
        this.xPublic = xPublic;
        this.xPrivate = xPrivate;
        this.stored = stored;
    }

    /**
     * Creates a brand-new identity from fresh key pairs, sealed under {@code passphrase}.
     *
     * @param passphrase the passphrase protecting the private material
     * @return the unlocked identity
     * @throws CryptoException if key generation or sealing fails
     */
    public static VaultIdentity create(char[] passphrase) throws CryptoException {
        var ed = Ed25519.newKeyPair();
        var x = X25519.newKeyPair();
        byte[] material = concat(Ed25519.encodePrivateKey(ed.getPrivate()), X25519.encodePrivateKey(x.getPrivate()));
        byte[] salt = Pbkdf2.newSalt();
        SecretKey mk = Pbkdf2.deriveKey(passphrase, salt, Pbkdf2.VAULT_ITERATIONS);
        String wrapped = seal(mk, PASSPHRASE_AAD, material);
        Base64.Encoder b64 = Base64.getEncoder();
        WrappedIdentity stored = new WrappedIdentity(
                b64.encodeToString(Ed25519.encodePublicKey(ed.getPublic())),
                b64.encodeToString(X25519.encodePublicKey(x.getPublic())),
                b64.encodeToString(salt),
                Pbkdf2.VAULT_ITERATIONS,
                wrapped,
                null,
                null);
        Arrays.fill(material, (byte) 0);
        return new VaultIdentity(ed.getPublic(), ed.getPrivate(), x.getPublic(), x.getPrivate(), stored);
    }

    /**
     * Unlocks a stored identity with its passphrase.
     *
     * @param stored the stored identity
     * @param passphrase the passphrase used at creation
     * @return the unlocked identity
     * @throws CryptoException if the passphrase is wrong or the material is corrupt
     */
    public static VaultIdentity unlock(WrappedIdentity stored, char[] passphrase) throws CryptoException {
        SecretKey mk =
                Pbkdf2.deriveKey(passphrase, Base64.getDecoder().decode(stored.kdfSalt()), stored.kdfIterations());
        byte[] material = open(mk, PASSPHRASE_AAD, stored.wrappedPrivateMaterial());
        return fromMaterial(material, stored);
    }

    /**
     * Restores a stored identity from its offline recovery code, bypassing the passphrase.
     *
     * @param stored the stored identity (must carry a recovery sealing)
     * @param recoveryCode the recovery code shown at recovery setup
     * @return the unlocked identity
     * @throws CryptoException if no recovery sealing exists or the code is wrong
     */
    public static VaultIdentity unlockWithRecovery(WrappedIdentity stored, String recoveryCode) throws CryptoException {
        if (!stored.hasRecovery()) {
            throw new CryptoException("identity has no recovery sealing");
        }
        SecretKey rk = RecoveryCode.deriveKey(recoveryCode, Base64.getDecoder().decode(stored.recoverySalt()));
        byte[] material = open(rk, RECOVERY_AAD, stored.recoveryWrappedPrivateMaterial());
        return fromMaterial(material, stored);
    }

    /**
     * Returns a copy of this identity's stored form with an offline recovery sealing added (or
     * replaced) under {@code recoveryCode}. Persist the returned record; the live identity is unchanged.
     *
     * @param recoveryCode the recovery code (see {@link RecoveryCode#newCode()})
     * @return the stored form including the recovery sealing
     * @throws CryptoException if sealing fails
     */
    public WrappedIdentity withRecovery(String recoveryCode) throws CryptoException {
        byte[] material = concat(Ed25519.encodePrivateKey(edPrivate), X25519.encodePrivateKey(xPrivate));
        byte[] salt = Pbkdf2.newSalt();
        SecretKey rk = RecoveryCode.deriveKey(recoveryCode, salt);
        String recoveryWrapped = seal(rk, RECOVERY_AAD, material);
        Arrays.fill(material, (byte) 0);
        return new WrappedIdentity(
                stored.ed25519PublicKey(),
                stored.x25519PublicKey(),
                stored.kdfSalt(),
                stored.kdfIterations(),
                stored.wrappedPrivateMaterial(),
                Base64.getEncoder().encodeToString(salt),
                recoveryWrapped);
    }

    /**
     * Returns the stored, passphrase-protected form of this identity for persistence or upload.
     *
     * @return the wrapped identity
     */
    public WrappedIdentity export() {
        return stored;
    }

    /**
     * Returns the Ed25519 identity public key — this member's stable identity.
     *
     * @return the identity public key
     */
    public PublicKey identityPublicKey() {
        return edPublic;
    }

    /**
     * Returns the raw 32-byte Ed25519 identity public key, Base64-encoded — the member id used on the
     * wire and to key membership server-side.
     *
     * @return the Base64 identity public key
     * @throws CryptoException if encoding fails
     */
    public String identityId() throws CryptoException {
        return Base64.getEncoder().encodeToString(Ed25519.encodePublicKey(edPublic));
    }

    /**
     * Returns the X25519 public key, to which other members wrap repository data keys for this member.
     *
     * @return the key-agreement public key
     */
    public PublicKey keyAgreementPublicKey() {
        return xPublic;
    }

    /**
     * Returns the X25519 private key, used to unwrap repository data keys wrapped to this member.
     *
     * @return the key-agreement private key
     */
    public PrivateKey keyAgreementPrivateKey() {
        return xPrivate;
    }

    /**
     * Signs a server-issued challenge nonce with the Ed25519 identity key, proving key ownership for
     * authentication.
     *
     * @param nonce the challenge bytes
     * @return the signature
     * @throws CryptoException if signing fails
     */
    public byte[] signChallenge(byte[] nonce) throws CryptoException {
        return Ed25519.sign(edPrivate, nonce);
    }

    private static VaultIdentity fromMaterial(byte[] material, WrappedIdentity stored) throws CryptoException {
        if (material.length != MATERIAL_BYTES) {
            throw new CryptoException("identity material must be " + MATERIAL_BYTES + " bytes");
        }
        byte[] edSeed = Arrays.copyOfRange(material, 0, Ed25519.KEY_BYTES);
        byte[] xScalar = Arrays.copyOfRange(material, Ed25519.KEY_BYTES, MATERIAL_BYTES);
        Base64.Decoder b64 = Base64.getDecoder();
        VaultIdentity identity = new VaultIdentity(
                Ed25519.decodePublicKey(b64.decode(stored.ed25519PublicKey())),
                Ed25519.decodePrivateKey(edSeed),
                X25519.decodePublicKey(b64.decode(stored.x25519PublicKey())),
                X25519.decodePrivateKey(xScalar),
                stored);
        Arrays.fill(material, (byte) 0);
        Arrays.fill(edSeed, (byte) 0);
        Arrays.fill(xScalar, (byte) 0);
        return identity;
    }

    private static String seal(SecretKey key, byte[] aad, byte[] plaintext) throws CryptoException {
        byte[] iv = PayloadCipher.newIv();
        byte[] ct = PayloadCipher.encrypt(key, iv, aad, plaintext);
        return Base64.getEncoder().encodeToString(concat(iv, ct));
    }

    private static byte[] open(SecretKey key, byte[] aad, String sealedB64) throws CryptoException {
        byte[] sealed = Base64.getDecoder().decode(sealedB64);
        if (sealed.length <= PayloadCipher.IV_BYTES) {
            throw new CryptoException("sealed material too short");
        }
        byte[] iv = Arrays.copyOfRange(sealed, 0, PayloadCipher.IV_BYTES);
        byte[] ct = Arrays.copyOfRange(sealed, PayloadCipher.IV_BYTES, sealed.length);
        return PayloadCipher.decrypt(key, iv, aad, ct);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
