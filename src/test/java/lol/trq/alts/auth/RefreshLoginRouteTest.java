package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.auth.AltLoginCallback.LoginResult;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.BanInfo;
import lol.trq.alts.model.LoginMode;
import lol.trq.alts.model.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefreshLoginRouteTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String UUID = "00000000-0000-4000-8000-000000000001";

    @TempDir
    Path vaultDir;

    private HttpServer server;
    private MicrosoftAuthConfig config;
    private final AtomicReference<SessionData> injected = new AtomicReference<>();
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>("");
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger profileCalls = new AtomicInteger();
    private final AtomicBoolean refuseProfileOnce = new AtomicBoolean();

    private int tokenStatus = 200;
    private String tokenBody = "{\"access_token\":\"ms-access\",\"refresh_token\":\"rotated\",\"expires_in\":3600}";
    private int profileStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        AltStoreTestSupport.bindTo(vaultDir);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            tokenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, tokenStatus, tokenBody);
        });
        server.createContext("/xbl", exchange -> respond(exchange, 200, "{\"Token\":\"xbl\"}"));
        server.createContext(
                "/xsts",
                exchange -> respond(
                        exchange, 200, "{\"Token\":\"xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}"));
        server.createContext(
                "/mclogin",
                exchange -> respond(exchange, 200, "{\"access_token\":\"mc-access\",\"expires_in\":86400}"));
        server.createContext("/mcprofile", exchange -> {
            int call = profileCalls.incrementAndGet();
            int status = profileStatus;
            if (refuseProfileOnce.get() && call == 1) {
                status = 401;
            }
            respond(
                    exchange,
                    status,
                    status == 200 ? "{\"id\":\"00000000000040008000000000000001\",\"name\":\"Alex\"}" : "{}");
        });
        server.start();

        String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort();
        config = MicrosoftAuthConfig.of("test-client-id")
                .withEndpoints(
                        base + "/authorize",
                        base + "/token",
                        base + "/xbl",
                        base + "/xsts",
                        base + "/mclogin",
                        base + "/mcprofile");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private AltLoginServiceImpl service() {
        return new AltLoginServiceImpl(injected::set, config, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void refreshRouteLogsInAndStoresTheRotatedToken() throws Exception {
        LoginResult result =
                service().loginRefreshToken("original", LoginMode.ADD).get();

        assertTrue(result.success(), result.message());
        assertEquals("Alex", result.account().username());
        assertEquals("rotated", result.account().refreshToken());
        assertEquals(AccountType.MICROSOFT, result.account().type());
        assertEquals("Alex", injected.get().username());

        // LoginMode.ADD is a promise to save. Asserting only on the returned record would hold even if
        // the route never wrote anything, which is the whole difference between ADD and DIRECT.
        AltAccount persisted = AltStoreTestSupport.reloadFromDiskAndFind(UUID);
        assertEquals("rotated", persisted.refreshToken(), "ADD must write the imported credential to the store");
        assertEquals("mc-access", persisted.accessToken());
        assertEquals(AccountType.MICROSOFT, persisted.type());
    }

    /**
     * Refresh tokens are pasted, and they are pasted out of account lists that prefix them with a name,
     * out of HTTP headers, and out of quoted config values. The surrounding text is invisible in a
     * password-style field, so it reaches the token endpoint as part of the credential and comes back
     * as an invalid grant — indistinguishable, to the user, from a genuinely dead token.
     */
    @Test
    void aPastedTokenIsSanitisedBeforeItReachesTheTokenEndpoint() throws Exception {
        // Shaped like a Microsoft account token; deliberately not a real one.
        String token = "M.C500_EXAMPLE.0.U.NotARealToken";

        for (String pasted : new String[] {
            "listedname:" + token, "Bearer " + token, "bearer " + token, "  " + token + "  ", "\"" + token + "\""
        }) {
            tokenRequestBody.set("");
            LoginResult result =
                    service().loginRefreshToken(pasted, LoginMode.DIRECT).get();

            assertTrue(result.success(), "paste form <" + pasted + "> failed: " + result.message());
            assertTrue(
                    tokenRequestBody.get().contains("refresh_token=" + token + "&"),
                    "paste form <" + pasted + "> reached the wire uncleaned: " + tokenRequestBody.get());
        }
    }

    @Test
    void aTokenWithNoSurroundingTextIsLeftAlone() throws Exception {
        String token = "M.C500_EXAMPLE.0.U.NotARealToken";

        service().loginRefreshToken(token, LoginMode.DIRECT).get();

        assertTrue(
                tokenRequestBody.get().contains("refresh_token=" + token + "&"),
                "a clean token must pass through untouched: " + tokenRequestBody.get());
    }

    @Test
    void reimportingARefreshTokenMergesOntoTheStoredRecord() throws Exception {
        AltAccount stored = AltAccount.of(UUID, "Alex", "old-access", AccountType.MICROSOFT)
                .withTokens("old-access", "old-refresh", NOW - 1)
                .withBan("serverone", BanInfo.observed("self", "x"))
                .withSource("democlient", "user1");
        AltStoreTestSupport.seed(stored);

        LoginResult result =
                service().loginRefreshToken("original", LoginMode.ADD).get();

        assertTrue(result.success(), result.message());
        assertEquals("rotated", result.account().refreshToken());
        assertTrue(result.account().banned("serverone"), "the installed record must keep what the store knew");
        assertEquals("democlient", result.account().sourceClient(), "the installed record must keep its provenance");

        AltAccount persisted = AltStoreTestSupport.reloadFromDiskAndFind(UUID);
        assertEquals("rotated", persisted.refreshToken(), "the imported credential must land");
        assertEquals("mc-access", persisted.accessToken());
        assertTrue(persisted.banned("serverone"), "re-importing an alt must not wipe observed bans");
        assertEquals("democlient", persisted.sourceClient(), "re-importing an alt must not wipe provenance");
        assertEquals("user1", persisted.sourceUser(), "re-importing an alt must not wipe provenance");
    }

    @Test
    void refreshRouteRequiresConfiguration() throws Exception {
        AltLoginServiceImpl unconfigured =
                new AltLoginServiceImpl(injected::set, null, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        LoginResult result =
                unconfigured.loginRefreshToken("original", LoginMode.ADD).get();

        assertFalse(result.success());
        assertEquals(FailureReason.NOT_CONFIGURED, result.reason());
    }

    @Test
    void expiredAccountRenewsAndPersistsTheRotatedToken() throws Exception {
        AltAccount stored = AltAccount.of(UUID, "Alex", "stale", AccountType.MICROSOFT)
                .withTokens("stale", "stored-refresh", NOW - 1)
                .withBan("serverone", BanInfo.observed("self", "x"))
                .withSource("democlient", "user1");
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertTrue(result.success(), result.message());
        assertEquals(1, tokenCalls.get(), "an expired account renews exactly once");
        assertTrue(
                tokenRequestBody.get().contains("refresh_token=stored-refresh"),
                "the stored token must reach the wire: " + tokenRequestBody.get());
        assertEquals("mc-access", result.account().accessToken());
        assertEquals("rotated", result.account().refreshToken(), "the rotated token replaces the stored one");

        AltAccount persisted = AltStoreTestSupport.find(UUID);
        assertEquals("rotated", persisted.refreshToken(), "the rotated token must reach disk, not just memory");
        assertEquals(AccountType.MICROSOFT, persisted.type(), "renewal must not change the account type");
        assertTrue(persisted.banned("serverone"), "renewal must preserve shared ban records");
        assertEquals("democlient", persisted.sourceClient(), "renewal must preserve provenance");

        // The renewed session is a Minecraft token (~24h), reached through a Microsoft OAuth token (~1h).
        // Stamping the OAuth lifetime would mark the account expired twenty-three hours early and make
        // every subsequent login pay a renewal it does not need.
        long stampedLifetime = persisted.expiresAt() - System.currentTimeMillis();
        assertTrue(
                stampedLifetime > 80_000_000L,
                "the persisted expiry must describe the Minecraft session, not the OAuth token: " + stampedLifetime);
        assertTrue(
                stampedLifetime <= 86_400_000L,
                "the persisted expiry must not exceed the advertised lifetime: " + stampedLifetime);

        // Installing the session only mutates the in-memory list, so the disk round-trip is what proves
        // the rotated token survives a restart rather than dying on the second renewal.
        AltAccount reloaded = AltStoreTestSupport.reloadFromDiskAndFind(UUID);
        assertEquals(
                "rotated",
                reloaded.refreshToken(),
                "the rotated token must be written to the store, not only held in memory");
        assertEquals(persisted.expiresAt(), reloaded.expiresAt(), "the stamped expiry must survive a restart too");
    }

    @Test
    void aFailingHostInjectorDoesNotSpendARefreshTokenRotation() throws Exception {
        AltAccount stored = AltAccount.of(UUID, "Alex", "opaque-live", AccountType.MICROSOFT)
                .withTokens("opaque-live", "stored-refresh", NOW + 3_600_000L);
        AltStoreTestSupport.seed(stored);

        AltLoginServiceImpl broken = new AltLoginServiceImpl(
                session -> {
                    throw new IllegalStateException("host injector broke");
                },
                config,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        LoginResult result = broken.loginAccount(stored).get();

        assertFalse(result.success());
        assertEquals(FailureReason.UNKNOWN, result.reason(), "a broken injector is not a refused token");
        assertEquals(0, tokenCalls.get(), "only a refused token may trigger a renewal");
        assertEquals(
                "stored-refresh",
                AltStoreTestSupport.find(UUID).refreshToken(),
                "a host-side failure must not spend the user's one rotation");
    }

    @Test
    void liveAccountInjectsTheStoredRecordWithoutStrippingIt() throws Exception {
        AltAccount stored = AltAccount.of(UUID, "Alex", "opaque-live", AccountType.MICROSOFT)
                .withTokens("opaque-live", "stored-refresh", NOW + 3_600_000L)
                .withBan("serverone", BanInfo.observed("self", "x"));
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertTrue(result.success(), result.message());
        assertEquals(0, tokenCalls.get(), "a live token must not trigger a renewal");

        // Regression guard: routing this path through loginSession would rebuild the account as
        // AccountType.SESSION with a null refresh token, permanently disabling renewal.
        assertEquals("stored-refresh", result.account().refreshToken(), "a live login must not drop the credential");
        assertEquals(AccountType.MICROSOFT, result.account().type(), "a live login must not retype the account");
        assertEquals("stored-refresh", AltStoreTestSupport.find(UUID).refreshToken());
        assertEquals(AccountType.MICROSOFT, AltStoreTestSupport.find(UUID).type());
        assertTrue(AltStoreTestSupport.find(UUID).banned("serverone"), "a live login must preserve bans");
    }

    @Test
    void connectionFailureIsTransientAndKeepsTheStoredToken() throws Exception {
        AltAccount stored =
                AltAccount.of(UUID, "Alex", "stale", AccountType.MICROSOFT).withTokens("stale", "still-good", NOW - 1);
        AltStoreTestSupport.seed(stored);

        // Point the token endpoint at a port nothing is listening on.
        server.stop(0);
        server = null;

        LoginResult result = service().loginAccount(stored).get();

        assertFalse(result.success());
        assertEquals(FailureReason.NETWORK, result.reason());
        assertEquals(
                "still-good",
                AltStoreTestSupport.find(UUID).refreshToken(),
                "an unreachable service must not cost the user their credential");
    }

    @Test
    void rejectedAccessTokenRenewsOnceThenSucceeds() throws Exception {
        // The stored token is live by the clock but refused by the service — a token revoked early, as
        // happens after a password change. The first profile fetch refuses; the renewed chain's fetch
        // succeeds, so the reactive path is genuinely exercised end to end.
        refuseProfileOnce.set(true);
        AltAccount stored = AltAccount.of(UUID, "Alex", "opaque-live", AccountType.MICROSOFT)
                .withTokens("opaque-live", "stored-refresh", NOW + 3_600_000L);
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertTrue(result.success(), result.message());
        assertEquals(1, tokenCalls.get(), "renewal is attempted exactly once, never in a loop");
        assertEquals("rotated", AltStoreTestSupport.find(UUID).refreshToken());
    }

    @Test
    void aSecondRejectionFailsRatherThanLooping() throws Exception {
        profileStatus = 401;
        AltAccount stored = AltAccount.of(UUID, "Alex", "opaque-live", AccountType.MICROSOFT)
                .withTokens("opaque-live", "stored-refresh", NOW + 3_600_000L);
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertFalse(result.success(), "the renewed chain saw the same refusing endpoint");
        assertEquals(1, tokenCalls.get(), "renewal must not retry itself");
    }

    @Test
    void permanentRejectionClearsTheStoredRefreshTokenAndAsksForReauth() throws Exception {
        tokenStatus = 400;
        tokenBody = "{\"error\":\"invalid_grant\"}";
        AltAccount stored = AltAccount.of(UUID, "Alex", "stale", AccountType.MICROSOFT)
                .withTokens("stale", "revoked-refresh", NOW - 1);
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertFalse(result.success());
        assertEquals(FailureReason.REAUTH_REQUIRED, result.reason());
        assertNull(
                AltStoreTestSupport.find(stored.uuid()).refreshToken(),
                "a permanently rejected token must not stay on disk");
    }

    @Test
    void transientRejectionKeepsTheStoredRefreshToken() throws Exception {
        tokenStatus = 503;
        tokenBody = "unavailable";
        AltAccount stored =
                AltAccount.of(UUID, "Alex", "stale", AccountType.MICROSOFT).withTokens("stale", "still-good", NOW - 1);
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertFalse(result.success());
        assertEquals(FailureReason.NETWORK, result.reason());
        assertEquals(
                "still-good",
                AltStoreTestSupport.find(stored.uuid()).refreshToken(),
                "an outage must not cost the user their credential");
    }
}
