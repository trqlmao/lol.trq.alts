package lol.trq.alts.vault.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.List;
import lol.trq.alts.crypto.WrappedKey;
import org.junit.jupiter.api.Test;

/** Gson round-trips for the wire DTOs, proving every component carries a working {@code @SerializedName}. */
class TransportDtoSerializationTest {

    private static final Gson GSON = new GsonBuilder().create();

    private static WrappedKey wrappedKey() {
        return new WrappedKey("X25519-HKDF-SHA256-AESGCM-v1", "ZXBo", "aXY=", "Y3Q=");
    }

    @Test
    void memberEntryRoundTrips() {
        MemberEntry entry = new MemberEntry("edPub", "xPub", wrappedKey(), 3L);
        assertEquals(entry, GSON.fromJson(GSON.toJson(entry), MemberEntry.class));
    }

    @Test
    void memberEntryWithKeyBindingSigRoundTrips() {
        MemberEntry entry = new MemberEntry("edPub", "xPub", wrappedKey(), 3L, "c2ln");
        MemberEntry restored = GSON.fromJson(GSON.toJson(entry), MemberEntry.class);
        assertEquals(entry, restored);
        assertEquals("c2ln", restored.keyBindingSig());
    }

    @Test
    void vaultManifestRoundTrips() {
        VaultManifest manifest =
                new VaultManifest("repo-1", "scheme-v1", 2L, 7L, List.of(new MemberEntry("ed", "x", wrappedKey(), 2L)));
        assertEquals(manifest, GSON.fromJson(GSON.toJson(manifest), VaultManifest.class));
    }

    @Test
    void encryptedEnvelopeRoundTrips() {
        EncryptedEnvelope env = new EncryptedEnvelope("repo-1", 9L, 2L, "aXY=", "Y2lwaGVy");
        assertEquals(env, GSON.fromJson(GSON.toJson(env), EncryptedEnvelope.class));
    }

    @Test
    void pushRequestAndResponseRoundTrip() {
        PushRequest req = new PushRequest(
                "repo-1",
                new EncryptedEnvelope("repo-1", 9L, 2L, "aXY=", "Y3Q="),
                8L,
                List.of(new MemberEntry("ed", "x", wrappedKey(), 2L)));
        assertEquals(req, GSON.fromJson(GSON.toJson(req), PushRequest.class));

        PushResponse resp = new PushResponse(true, 9L, 2L, false);
        assertEquals(resp, GSON.fromJson(GSON.toJson(resp), PushResponse.class));
    }

    @Test
    void authDtosRoundTrip() {
        TokenRequest tr = new TokenRequest("edPub", "bm9uY2U=", "c2ln");
        assertEquals(tr, GSON.fromJson(GSON.toJson(tr), TokenRequest.class));

        AuthToken token = new AuthToken("jwt.value.here", 1_700_000_000_000L);
        assertEquals(token, GSON.fromJson(GSON.toJson(token), AuthToken.class));
    }

    @Test
    void memberRemoveRequestRoundTrips() {
        MemberRemoveRequest req = new MemberRemoveRequest(
                "repo-1",
                "carol-id",
                new EncryptedEnvelope("repo-1", 10L, 1L, "aXY=", "Y3Q="),
                List.of(new MemberEntry("ed", "x", wrappedKey(), 1L)),
                1L);
        assertEquals(req, GSON.fromJson(GSON.toJson(req), MemberRemoveRequest.class));
    }
}
