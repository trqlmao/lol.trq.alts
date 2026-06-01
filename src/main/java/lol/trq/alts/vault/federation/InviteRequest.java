package lol.trq.alts.vault.federation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import lol.trq.alts.vault.MemberPublicKey;

/**
 * The first half of a federated join handshake: a prospective member's public keys, which they hand to
 * an existing member out of band (a copy-pasteable token). Because adding a member is member-initiated —
 * the inviter wraps the repository data key to the joiner's X25519 key — the joiner must publish their
 * keys first. The inviter decodes this, calls {@code SharedVault.addMember}, and replies with a
 * {@link RepoLocator}. Carrying only public keys, the token is safe to relay.
 *
 * @param version the token format version
 * @param ed25519PublicKey the joiner's Ed25519 identity key, Base64 (their member id)
 * @param x25519PublicKey the joiner's X25519 key-agreement key, Base64
 * @author trq
 * @since 0.2.0
 */
public record InviteRequest(
        @SerializedName("v") int version,
        @SerializedName("ed25519PublicKey") String ed25519PublicKey,
        @SerializedName("x25519PublicKey") String x25519PublicKey) {

    /** The current token format version. */
    public static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder().create();

    /** Validates the required keys. */
    public InviteRequest {
        Objects.requireNonNull(ed25519PublicKey, "ed25519PublicKey");
        Objects.requireNonNull(x25519PublicKey, "x25519PublicKey");
    }

    /**
     * Builds an invite request for the given member's public keys at the current version.
     *
     * @param member the joiner's public keys
     * @return the invite request
     */
    public static InviteRequest forMember(MemberPublicKey member) {
        Objects.requireNonNull(member, "member");
        return new InviteRequest(VERSION, member.ed25519PublicKey(), member.x25519PublicKey());
    }

    /**
     * Returns these keys as a {@link MemberPublicKey} for {@code SharedVault.addMember}.
     *
     * @return the member public keys
     */
    public MemberPublicKey toMemberPublicKey() {
        return new MemberPublicKey(ed25519PublicKey, x25519PublicKey);
    }

    /**
     * Encodes this request as a base64url token (no padding).
     *
     * @return the encoded token
     */
    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a base64url token produced by {@link #encode()}.
     *
     * @param token the encoded token
     * @return the decoded invite request
     * @throws IllegalArgumentException if the token is not a well-formed invite request
     */
    public static InviteRequest decode(String token) {
        Objects.requireNonNull(token, "token");
        try {
            byte[] json = Base64.getUrlDecoder().decode(token);
            InviteRequest decoded = GSON.fromJson(new String(json, StandardCharsets.UTF_8), InviteRequest.class);
            if (decoded == null || decoded.ed25519PublicKey == null || decoded.x25519PublicKey == null) {
                throw new IllegalArgumentException("malformed invite token");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            // Gson runs the canonical constructor, whose requireNonNull throws NPE on missing keys;
            // a bad JSON shape throws JsonSyntaxException. Both mean a malformed token.
            throw new IllegalArgumentException("malformed invite token", e);
        }
    }
}
