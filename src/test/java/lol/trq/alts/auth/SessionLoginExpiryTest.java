package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import lol.trq.alts.model.LoginMode;
import lol.trq.alts.model.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session route decodes a JWT's claims to resolve an identity without a network call. That
 * shortcut must be gated on the token still being alive: an expired token that reports success installs
 * a dead session and fails much later, at the server, with nothing to diagnose it by.
 *
 * <p>Both tests point at a profile endpoint that refuses everything, so the only route to a successful
 * result is the fast path itself.
 */
class SessionLoginExpiryTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String UNDASHED_UUID = "00000000000040008000000000000001";

    @TempDir
    Path vaultDir;

    private HttpServer server;
    private MicrosoftAuthConfig config;
    private final AtomicReference<SessionData> injected = new AtomicReference<>();
    private final AtomicInteger profileCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        AltStoreTestSupport.bindTo(vaultDir);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mcprofile", exchange -> {
            profileCalls.incrementAndGet();
            respond(exchange, 401, "{}");
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

    /** Builds a JWT carrying the claims the fast path reads, expiring at the given epoch millis. */
    private static String jwtExpiringAt(long epochMillis) {
        String payload = "{\"name\":\"Alex\",\"id\":\"" + UNDASHED_UUID + "\",\"exp\":" + (epochMillis / 1000L) + "}";
        return "eyJhbGciOiJIUzI1NiJ9."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".sig";
    }

    private AltLoginServiceImpl service() {
        return new AltLoginServiceImpl(injected::set, config, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void aLiveJwtResolvesOnTheFastPathWithoutAskingTheProfileEndpoint() throws Exception {
        LoginResult result = service()
                .loginSession(jwtExpiringAt(NOW + 3_600_000L), LoginMode.DIRECT)
                .get();

        assertTrue(result.success(), result.message());
        assertEquals("Alex", result.account().username());
        assertEquals(0, profileCalls.get(), "a live JWT is resolved from its own claims");
    }

    @Test
    void anExpiredJwtDoesNotReportSuccessOnTheFastPath() throws Exception {
        LoginResult result = service()
                .loginSession(jwtExpiringAt(NOW - 3_600_000L), LoginMode.DIRECT)
                .get();

        assertFalse(result.success(), "an expired token must never install a session that is already dead");
        assertEquals(1, profileCalls.get(), "an expired token falls through to validation instead");
    }

    @Test
    void aJwtExpiringInsideTheSkewMarginIsAlsoRefused() throws Exception {
        LoginResult result = service()
                .loginSession(jwtExpiringAt(NOW + TokenExpiry.SKEW_MILLIS - 1_000L), LoginMode.DIRECT)
                .get();

        assertFalse(result.success(), "a token that would lapse mid-handshake must not be taken as live");
    }
}
