package lol.trq.alts.vault;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lol.trq.alts.crypto.CryptoException;
import lol.trq.alts.crypto.KeyWrapScheme;
import lol.trq.alts.crypto.PayloadCipher;
import lol.trq.alts.crypto.RepoDataKey;
import lol.trq.alts.crypto.VaultIdentity;
import lol.trq.alts.crypto.X25519;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.vault.transport.EncryptedEnvelope;
import lol.trq.alts.vault.transport.MemberEntry;
import lol.trq.alts.vault.transport.VaultManifest;

/**
 * The local, transport-free crypto facade for a shared alt repository. Every method here is pure
 * client-side cryptography over plain DTOs — it creates and opens repositories, wraps the data key to
 * members, and encrypts/decrypts the alt payload. Moving those DTOs to and from a server is a separate
 * concern handled by a {@link lol.trq.alts.vault.transport.VaultTransport}; this class never touches
 * the network, which keeps it trivially unit-testable and keeps the zero-knowledge guarantee provable:
 * the server only ever sees what these methods emit.
 *
 * <p>The payload carries {@link AltAccount}s — the credential plus shareable account state
 * (last-used activity and observed-ban status, so members coordinate and avoid handing each other a
 * banned alt). What stays local and never enters a repo is the per-member current-account pointer and
 * the user's own secrets (kept in {@link lol.trq.alts.store.SecretStore}).
 *
 * @author trq
 * @since 0.2.0
 */
public final class SharedVault {

    private static final Gson GSON = new GsonBuilder().create();

    private final KeyWrapScheme scheme;

    /**
     * Creates a vault facade over the given key-wrap scheme.
     *
     * @param scheme the key-wrap scheme (for example {@link lol.trq.alts.crypto.X25519HkdfAesGcmKeyWrap})
     */
    public SharedVault(KeyWrapScheme scheme) {
        this.scheme = scheme;
    }

    /**
     * The product of creating a repository: the working context, the seed manifest to upload, and the
     * initial encrypted payload.
     *
     * @param context the working context for the creator
     * @param manifest the seed manifest (creator-only roster)
     * @param envelope the initial encrypted payload
     */
    public record CreatedRepo(RepoContext context, VaultManifest manifest, EncryptedEnvelope envelope) {}

    /**
     * The product of a key rotation: the advanced context, the re-encrypted payload, the re-wrapped
     * member roster, and the new epoch.
     *
     * @param context the advanced working context (new key, bumped version)
     * @param envelope the payload re-encrypted under the new epoch
     * @param rewrappedMembers the remaining members with the new key wrapped to each
     * @param newKeyEpoch the new key epoch
     */
    public record RotationResult(
            RepoContext context, EncryptedEnvelope envelope, List<MemberEntry> rewrappedMembers, long newKeyEpoch) {}

    /**
     * Creates a fresh repository owned by {@code creator}, with the given initial alts.
     *
     * @param creator the creating member's unlocked identity
     * @param alts the initial alt payload (may be empty)
     * @return the created repository artifacts
     * @throws CryptoException if key generation or encryption fails
     */
    public CreatedRepo createRepo(VaultIdentity creator, List<AltAccount> alts) throws CryptoException {
        String repoId = UUID.randomUUID().toString();
        RepoDataKey dataKey = RepoDataKey.generate();
        MemberEntry creatorEntry = memberEntry(creator.identityId(), creator.keyAgreementPublicKey(), dataKey);
        long version = 1L;
        VaultManifest manifest =
                new VaultManifest(repoId, scheme.schemeId(), dataKey.epoch(), version, List.of(creatorEntry));
        EncryptedEnvelope envelope = encrypt(repoId, version, dataKey.epoch(), dataKey, alts);
        RepoContext context = new RepoContext(repoId, creator, dataKey, version);
        return new CreatedRepo(context, manifest, envelope);
    }

    /**
     * Opens a repository for {@code identity} by unwrapping the data key from their member entry.
     *
     * @param manifest the repository manifest
     * @param identity the opening member's unlocked identity
     * @return the working context
     * @throws CryptoException if unwrapping fails
     * @throws VaultException if the identity is not a member of the repository
     */
    public RepoContext openRepo(VaultManifest manifest, VaultIdentity identity) throws CryptoException, VaultException {
        String memberId = identity.identityId();
        MemberEntry entry = manifest.findMember(memberId);
        if (entry == null) {
            throw new VaultException("identity is not a member of repository " + manifest.repoId());
        }
        RepoDataKey dataKey = RepoDataKey.unwrap(
                scheme, entry.wrappedDataKey(), identity.keyAgreementPrivateKey(), manifest.keyEpoch());
        return new RepoContext(manifest.repoId(), identity, dataKey, manifest.payloadVersion());
    }

    /**
     * Builds a member entry for a prospective member, wrapping the current data key to their X25519 key.
     *
     * @param ctx the working context
     * @param member the prospective member's public keys
     * @return the member entry to upload
     * @throws CryptoException if wrapping fails
     */
    public MemberEntry addMember(RepoContext ctx, MemberPublicKey member) throws CryptoException {
        PublicKey recipient = X25519.decodePublicKey(Base64.getDecoder().decode(member.x25519PublicKey()));
        return new MemberEntry(
                member.ed25519PublicKey(),
                member.x25519PublicKey(),
                ctx.dataKey().wrapTo(scheme, recipient),
                ctx.dataKey().epoch());
    }

    /**
     * Encrypts a new payload at {@code ctx.payloadVersion() + 1}. The caller advances its context with
     * {@link RepoContext#withPayloadVersion(long)} once the push is accepted.
     *
     * @param ctx the working context
     * @param alts the alts to encrypt
     * @return the encrypted envelope at the next version
     * @throws CryptoException if encryption fails
     */
    public EncryptedEnvelope encryptPayload(RepoContext ctx, List<AltAccount> alts) throws CryptoException {
        long nextVersion = ctx.payloadVersion() + 1;
        return encrypt(ctx.repoId(), nextVersion, ctx.dataKey().epoch(), ctx.dataKey(), alts);
    }

    /**
     * Decrypts a payload envelope, rejecting an envelope from a different repository or one that rolls
     * the version back below {@code expectedMinVersion}.
     *
     * @param ctx the working context
     * @param env the encrypted envelope
     * @param expectedMinVersion the lowest acceptable payload version (pass {@code ctx.payloadVersion()}
     *     to forbid rollback, or 0 to allow any)
     * @return the decrypted alts
     * @throws CryptoException if decryption fails (wrong key/epoch or tampering)
     * @throws VaultException if the repo id mismatches or the version is rolled back
     */
    public List<AltAccount> decryptPayload(RepoContext ctx, EncryptedEnvelope env, long expectedMinVersion)
            throws CryptoException, VaultException {
        if (!env.repoId().equals(ctx.repoId())) {
            throw new VaultException("envelope repo id does not match context");
        }
        if (env.payloadVersion() < expectedMinVersion) {
            throw new VaultException("payload rollback: " + env.payloadVersion() + " < " + expectedMinVersion);
        }
        byte[] iv = Base64.getDecoder().decode(env.iv());
        byte[] ciphertext = Base64.getDecoder().decode(env.ciphertext());
        byte[] aad = PayloadCipher.aad(env.repoId(), env.payloadVersion(), env.keyEpoch());
        byte[] plaintext = PayloadCipher.decrypt(ctx.dataKey().secretKey(), iv, aad, ciphertext);
        VaultPayload payload = GSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), VaultPayload.class);
        return payload == null || payload.alts() == null ? new ArrayList<>() : payload.alts();
    }

    /**
     * Rotates the data key to a fresh one at the next epoch, re-encrypts the payload, and re-wraps the
     * new key to {@code remainingMembers}. Use after a membership change so a departed member's old
     * wrapped key cannot decrypt anything new.
     *
     * @param ctx the working context
     * @param currentAlts the alts to carry into the new epoch
     * @param remainingMembers the members who retain access
     * @return the rotation result
     * @throws CryptoException if key generation, wrapping, or encryption fails
     */
    public RotationResult rotateKey(
            RepoContext ctx, List<AltAccount> currentAlts, List<MemberPublicKey> remainingMembers)
            throws CryptoException {
        long newEpoch = ctx.dataKey().epoch() + 1;
        RepoDataKey newDataKey = RepoDataKey.generate(newEpoch);
        List<MemberEntry> rewrapped = new ArrayList<>(remainingMembers.size());
        for (MemberPublicKey member : remainingMembers) {
            PublicKey recipient = X25519.decodePublicKey(Base64.getDecoder().decode(member.x25519PublicKey()));
            rewrapped.add(new MemberEntry(
                    member.ed25519PublicKey(),
                    member.x25519PublicKey(),
                    newDataKey.wrapTo(scheme, recipient),
                    newEpoch));
        }
        long newVersion = ctx.payloadVersion() + 1;
        RepoContext newCtx = new RepoContext(ctx.repoId(), ctx.identity(), newDataKey, newVersion);
        EncryptedEnvelope envelope = encrypt(ctx.repoId(), newVersion, newEpoch, newDataKey, currentAlts);
        return new RotationResult(newCtx, envelope, rewrapped, newEpoch);
    }

    /**
     * Removes a member: rotates the key to exactly the members in {@code remainingMembers} (which must
     * exclude the removed member). Equivalent to {@link #rotateKey} with the departed member dropped.
     *
     * @param ctx the working context
     * @param currentAlts the alts to carry forward
     * @param remainingMembers the members who retain access (excluding the removed one)
     * @return the rotation result
     * @throws CryptoException if rotation fails
     */
    public RotationResult removeMember(
            RepoContext ctx, List<AltAccount> currentAlts, List<MemberPublicKey> remainingMembers)
            throws CryptoException {
        return rotateKey(ctx, currentAlts, remainingMembers);
    }

    private MemberEntry memberEntry(String ed25519Id, PublicKey x25519Public, RepoDataKey dataKey)
            throws CryptoException {
        String x25519B64 = Base64.getEncoder().encodeToString(X25519.encodePublicKey(x25519Public));
        return new MemberEntry(ed25519Id, x25519B64, dataKey.wrapTo(scheme, x25519Public), dataKey.epoch());
    }

    private EncryptedEnvelope encrypt(
            String repoId, long version, long epoch, RepoDataKey dataKey, List<AltAccount> alts)
            throws CryptoException {
        String json = GSON.toJson(new VaultPayload(new ArrayList<>(alts), version));
        byte[] iv = PayloadCipher.newIv();
        byte[] aad = PayloadCipher.aad(repoId, version, epoch);
        byte[] ciphertext = PayloadCipher.encrypt(dataKey.secretKey(), iv, aad, json.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder b64 = Base64.getEncoder();
        return new EncryptedEnvelope(repoId, version, epoch, b64.encodeToString(iv), b64.encodeToString(ciphertext));
    }

    /**
     * The plaintext payload structure encrypted into an envelope: the alts plus a redundant version
     * stamp (the authoritative version is the envelope header, bound into the AAD).
     *
     * @param alts the shared alts
     * @param payloadVersion the version stamp
     */
    private record VaultPayload(
            @SerializedName("alts") List<AltAccount> alts,
            @SerializedName("payloadVersion") long payloadVersion) {}
}
