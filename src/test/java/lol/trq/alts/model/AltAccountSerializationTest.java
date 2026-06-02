package lol.trq.alts.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

class AltAccountSerializationTest {

    private final Gson gson = new GsonBuilder().create();

    /**
     * Gate for forward compatibility: a legacy account payload (written by an older {@code @Expose}-based
     * model) must still deserialize into the record. The field names are identical, so the only thing
     * under test is that {@code @SerializedName}-only Gson reads them.
     */
    @Test
    void parsesLegacyJsonShape() {
        String legacy =
                "{\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"username\":\"Steve\",\"accessToken\":\"tok\",\"type\":\"MICROSOFT\",\"lastUsed\":1717000000000}";

        AltAccount account = gson.fromJson(legacy, AltAccount.class);

        assertEquals("00000000-0000-0000-0000-000000000001", account.uuid());
        assertEquals("Steve", account.username());
        assertEquals("tok", account.accessToken());
        assertEquals(AccountType.MICROSOFT, account.type());
        assertEquals(1717000000000L, account.lastUsed());
        assertNull(account.bans(), "legacy files predate the bans field");
        assertNull(account.sourceClient(), "legacy files predate the sourceClient field");
        assertNull(account.sourceUser(), "legacy files predate the sourceUser field");
    }

    /**
     * A payload carrying a per-server {@code bans} map but no provenance must still load: the missing
     * {@code sourceClient}/{@code sourceUser} read as null, and the bans deserialize. (Migrating the
     * older single {@code ban} object is the store's job; see AltStoreBanMigrationTest.)
     */
    @Test
    void parsesBansWithoutProvenance() {
        String json =
                "{\"uuid\":\"u\",\"username\":\"Steve\",\"accessToken\":\"tok\",\"type\":\"MICROSOFT\",\"lastUsed\":1,\"bans\":{\"hypixel\":{\"banned\":true,\"observedAt\":2,\"source\":\"self\",\"detail\":\"d\",\"observedBy\":\"m\"}}}";

        AltAccount account = gson.fromJson(json, AltAccount.class);

        assertTrue(account.banned());
        assertTrue(account.banned("hypixel"));
        assertNull(account.sourceClient());
        assertNull(account.sourceUser());
    }

    @Test
    void withSourceSetsProvenanceAndRoundTrips() {
        AltAccount stamped =
                AltAccount.of("u", "Alex", "tok", AccountType.MICROSOFT).withSource("democlient", "user1");
        assertEquals("democlient", stamped.sourceClient());
        assertEquals("user1", stamped.sourceUser());

        AltAccount restored = gson.fromJson(gson.toJson(stamped), AltAccount.class);
        assertEquals(stamped, restored);
        assertEquals("democlient", restored.sourceClient());
        assertEquals("user1", restored.sourceUser());
    }

    @Test
    void roundTripsThroughGson() {
        AltAccount original = AltAccount.of("uuid-1", "Alex", "token-xyz", AccountType.SESSION);
        AltAccount restored = gson.fromJson(gson.toJson(original), AltAccount.class);
        assertEquals(original, restored);
    }

    @Test
    void usedNowKeepsIdentityFieldsAndAdvancesTimestamp() {
        AltAccount base = new AltAccount("u", "Notch", "t", AccountType.OFFLINE, 1L, null, null, "democlient", "user1");
        AltAccount stamped = base.usedNow();

        assertEquals(base.uuid(), stamped.uuid());
        assertEquals(base.username(), stamped.username());
        assertEquals(base.accessToken(), stamped.accessToken());
        assertEquals(base.type(), stamped.type());
        assertEquals(1L, base.lastUsed());
        assertEquals("democlient", stamped.sourceClient(), "usedNow preserves provenance");
        assertEquals("user1", stamped.sourceUser(), "usedNow preserves provenance");
    }

    @Test
    void perServerBanRoundTripsAndFlagsBanned() {
        AltAccount banned = AltAccount.of("u", "Herobrine", "t", AccountType.MICROSOFT)
                .withBan("hypixel", BanInfo.observed("self", "cheating"));
        assertTrue(banned.banned(), "banned on any");
        assertTrue(banned.banned("hypixel"));
        assertEquals(java.util.Set.of("hypixel"), banned.bannedServers());

        AltAccount restored = gson.fromJson(gson.toJson(banned), AltAccount.class);
        assertEquals(banned, restored);
        assertTrue(restored.banned("hypixel"));
        assertEquals("self", restored.bans().get("hypixel").source());
    }

    @Test
    void bansAreIndependentPerServer() {
        AltAccount alt = AltAccount.of("u", "Alex", "t", AccountType.MICROSOFT)
                .withBan("hypixel", BanInfo.observed("self", "x"))
                .withBan("cubecraft", BanInfo.observed("login", "y"));

        assertTrue(alt.banned("hypixel"));
        assertTrue(alt.banned("cubecraft"));
        assertFalse(alt.banned("mineplex"), "not banned on a server with no entry");
        assertEquals(java.util.Set.of("hypixel", "cubecraft"), alt.bannedServers());

        // Clearing one server leaves the other intact.
        AltAccount cleared = alt.withBan("hypixel", null);
        assertFalse(cleared.banned("hypixel"));
        assertTrue(cleared.banned("cubecraft"));
        assertTrue(cleared.banned(), "still banned somewhere");
    }
}
