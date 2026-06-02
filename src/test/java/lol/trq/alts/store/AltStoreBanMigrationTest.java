package lol.trq.alts.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Load-time migration of the legacy single {@code ban} field to the per-server {@code bans} map. */
class AltStoreBanMigrationTest {

    private static JsonObject firstAccount(String json) {
        return AltStore.normalizeLegacyBans(JsonParser.parseString(json))
                .getAsJsonObject()
                .getAsJsonArray("accounts")
                .get(0)
                .getAsJsonObject();
    }

    @Test
    void legacySingleBanMigratesToUnknownServer() {
        String legacy =
                "{\"accounts\":[{\"uuid\":\"u\",\"username\":\"x\",\"accessToken\":\"t\",\"type\":\"MICROSOFT\",\"lastUsed\":1,\"ban\":{\"banned\":true,\"observedAt\":2,\"source\":\"self\",\"detail\":\"d\"}}]}";

        JsonObject account = firstAccount(legacy);

        assertFalse(account.has("ban"), "legacy ban key removed");
        assertTrue(account.has("bans"));
        JsonObject bans = account.getAsJsonObject("bans");
        assertTrue(bans.has("unknown"), "old ban moved under the unknown server id");
        assertTrue(bans.getAsJsonObject("unknown").get("banned").getAsBoolean());
    }

    @Test
    void existingBansMapIsUntouched() {
        String current =
                "{\"accounts\":[{\"uuid\":\"u\",\"bans\":{\"hypixel\":{\"banned\":true,\"observedAt\":2,\"source\":\"self\",\"detail\":\"d\"}}}]}";

        JsonObject account = firstAccount(current);

        assertFalse(account.has("ban"));
        assertTrue(account.getAsJsonObject("bans").has("hypixel"));
    }

    @Test
    void noAccountsArrayIsNoOp() {
        JsonElement root = JsonParser.parseString("{}");
        assertSame(root, AltStore.normalizeLegacyBans(root));
    }
}
