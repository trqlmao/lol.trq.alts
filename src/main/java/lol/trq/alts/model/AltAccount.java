package lol.trq.alts.model;

import com.google.gson.annotations.SerializedName;

/**
 * An immutable Minecraft account stored locally within a client. Holds the credentials and metadata
 * required to reconstruct a game session. Persisted via Gson; every component carries a
 * {@link SerializedName} so serialization survives obfuscation.
 *
 * <p>When this account lives in a shared repository, {@code lastUsed}/{@code lastUsedBy} and {@code ban}
 * are shared state so members coordinate (who used it last, who got it banned). For a purely local
 * account the attribution fields are {@code null}.
 *
 * <p>{@code sourceClient}/{@code sourceUser} record provenance for cross-client sharing: which client
 * (mod) the alt was added from and the user within that client, so members of a shared repo can tell a
 * given alt came from, say, one client's user versus another's. They are plain, opaque, implementer-set
 * strings — the standard defines only the field names, never their values. Both are {@code null} for a
 * purely local or unattributed account, and they ride inside the encrypted repo payload only (the sync
 * server never sees them), preserving the zero-knowledge guarantee.
 *
 * @param uuid the unique identifier of the Minecraft player
 * @param username the last known display name of the account
 * @param accessToken the sensitive authentication token (OAuth, session, or cookie derived)
 * @param type the protocol used to authenticate this account
 * @param lastUsed the epoch-millis timestamp of the last successful login with this account
 * @param lastUsedBy the member id (Ed25519 identity) that last used this account in a shared repo, or
 *     {@code null} when used locally / unattributed
 * @param ban a locally-observed ban record, or {@code null} if the account is not known to be banned
 * @param sourceClient the client (mod) the alt was added from, or {@code null} when unattributed
 * @param sourceUser the user within {@code sourceClient} that added the alt, or {@code null}
 * @author trq
 * @since 0.1.0
 */
public record AltAccount(
        @SerializedName("uuid") String uuid,
        @SerializedName("username") String username,
        @SerializedName("accessToken") String accessToken,
        @SerializedName("type") AccountType type,
        @SerializedName("lastUsed") long lastUsed,
        @SerializedName("lastUsedBy") String lastUsedBy,
        @SerializedName("ban") BanInfo ban,
        @SerializedName("sourceClient") String sourceClient,
        @SerializedName("sourceUser") String sourceUser) {

    /**
     * Creates a freshly authenticated account stamped as used at the current system time, unattributed.
     *
     * @param uuid the player's UUID
     * @param username the player's username
     * @param accessToken the authentication token
     * @param type the authentication protocol used
     * @return a new account with {@code lastUsed} set to now
     */
    public static AltAccount of(String uuid, String username, String accessToken, AccountType type) {
        return new AltAccount(uuid, username, accessToken, type, System.currentTimeMillis(), null, null, null, null);
    }

    /**
     * Returns a copy of this account with {@code lastUsed} refreshed to now, preserving any existing
     * attribution.
     *
     * @return a copy stamped as used now
     */
    public AltAccount usedNow() {
        return new AltAccount(
                uuid,
                username,
                accessToken,
                type,
                System.currentTimeMillis(),
                lastUsedBy,
                ban,
                sourceClient,
                sourceUser);
    }

    /**
     * Returns a copy of this account stamped as used now by the given member — for attributing activity
     * inside a shared repository.
     *
     * @param byMember the member id (Ed25519 identity) that used the account, or {@code null}
     * @return a copy stamped as used now by {@code byMember}
     */
    public AltAccount usedNow(String byMember) {
        return new AltAccount(
                uuid, username, accessToken, type, System.currentTimeMillis(), byMember, ban, sourceClient, sourceUser);
    }

    /**
     * Returns a copy of this account carrying the given ban record.
     *
     * @param ban the ban record, or {@code null} to clear
     * @return a copy with {@code ban} replaced
     */
    public AltAccount withBan(BanInfo ban) {
        return new AltAccount(uuid, username, accessToken, type, lastUsed, lastUsedBy, ban, sourceClient, sourceUser);
    }

    /**
     * Returns a copy of this account stamped with its provenance — the client it was added from and the
     * user within that client — for cross-client shared repositories.
     *
     * @param sourceClient the client (mod) the alt was added from, or {@code null} to clear
     * @param sourceUser the user within {@code sourceClient} that added it, or {@code null}
     * @return a copy carrying the given provenance
     */
    public AltAccount withSource(String sourceClient, String sourceUser) {
        return new AltAccount(uuid, username, accessToken, type, lastUsed, lastUsedBy, ban, sourceClient, sourceUser);
    }

    /**
     * Returns whether this account is currently considered banned.
     *
     * @return {@code true} if a ban record is present and flags the account banned
     */
    public boolean banned() {
        return ban != null && ban.banned();
    }
}
