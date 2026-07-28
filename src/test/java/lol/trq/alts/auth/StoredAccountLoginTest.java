package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lol.trq.alts.auth.AltLoginCallback.LoginResult;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.BanInfo;
import lol.trq.alts.model.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Logging into an account that is already stored must install <em>that record</em>, never a poorer
 * rebuild of it, and must not spend a refresh-token rotation on a failure the credential did not cause.
 */
class StoredAccountLoginTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String UUID = "00000000-0000-4000-8000-000000000001";
    private static final String UNDASHED_UUID = "00000000000040008000000000000001";

    @TempDir
    Path vaultDir;

    private HttpServer server;
    private MicrosoftAuthConfig config;
    private final AtomicReference<SessionData> injected = new AtomicReference<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        AltStoreTestSupport.bindTo(vaultDir);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            respond(
                    exchange,
                    200,
                    "{\"access_token\":\"ms-access\",\"refresh_token\":\"rotated\",\"expires_in\":3600}");
        });
        server.createContext(
                "/mcprofile",
                exchange -> respond(exchange, 200, "{\"id\":\"" + UNDASHED_UUID + "\",\"name\":\"Alex\"}"));
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

    private static Clock at(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    /** A live JWT carrying the claims the fast path reads, so it resolves without any network call. */
    private static String liveJwt() {
        String payload =
                "{\"name\":\"Alex\",\"id\":\"" + UNDASHED_UUID + "\",\"exp\":" + ((NOW + 3_600_000L) / 1000L) + "}";
        return "eyJhbGciOiJIUzI1NiJ9."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".sig";
    }

    private AltLoginServiceImpl service() {
        return new AltLoginServiceImpl(injected::set, config, at(NOW));
    }

    @Test
    void anUnconfiguredMicrosoftLoginKeepsTheStoredRecordAndItsRefreshToken() throws Exception {
        String jwt = liveJwt();
        AltAccount stored = AltAccount.of(UUID, "Alex", jwt, AccountType.MICROSOFT)
                .withTokens(jwt, "stored-refresh", NOW + 3_600_000L)
                .withBan("serverone", BanInfo.observed("self", "x"))
                .withSource("democlient", "user1");
        AltStoreTestSupport.seed(stored);

        // No Microsoft configuration: renewal is impossible, but the stored record is still the record.
        AltLoginServiceImpl unconfigured = new AltLoginServiceImpl(injected::set, null, at(NOW));
        LoginResult result = unconfigured.loginAccount(stored).get();

        assertTrue(result.success(), result.message());
        assertEquals(AccountType.MICROSOFT, result.account().type(), "an unrenewable login must not retype the record");
        assertEquals("stored-refresh", result.account().refreshToken(), "the credential must survive the login");

        AltAccount persisted = AltStoreTestSupport.find(UUID);
        assertEquals("stored-refresh", persisted.refreshToken(), "the stored credential must not be replaced away");
        assertEquals(AccountType.MICROSOFT, persisted.type());
        assertTrue(persisted.banned("serverone"), "an unrenewable login must preserve observed bans");
        assertEquals("democlient", persisted.sourceClient(), "an unrenewable login must preserve provenance");
    }

    @Test
    void anAccountWithoutARefreshTokenKeepsItsTypeAndBans() throws Exception {
        String jwt = liveJwt();
        AltAccount stored = AltAccount.of(UUID, "Alex", jwt, AccountType.COOKIE)
                .withTokens(jwt, null, NOW + 3_600_000L)
                .withBan("serverone", BanInfo.observed("self", "x"))
                .withSource("democlient", "user1");
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertTrue(result.success(), result.message());
        assertEquals(AccountType.COOKIE, result.account().type(), "a cookie account must not be retyped as a session");

        AltAccount persisted = AltStoreTestSupport.find(UUID);
        assertEquals(AccountType.COOKIE, persisted.type());
        assertTrue(persisted.banned("serverone"), "a stored login must preserve observed bans");
        assertEquals("democlient", persisted.sourceClient(), "a stored login must preserve provenance");
        assertEquals(0, tokenCalls.get(), "an account with no refresh token has nothing to renew");
    }
}
