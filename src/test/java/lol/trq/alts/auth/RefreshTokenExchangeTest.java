package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RefreshTokenExchangeTest {

    private HttpServer server;
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
    private int tokenStatus = 200;
    private String tokenBody = "{\"access_token\":\"ms-access\",\"refresh_token\":\"rotated\",\"expires_in\":3600}";

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private MicrosoftAuthConfig startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> {
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
        server.createContext(
                "/mcprofile",
                exchange -> respond(exchange, 200, "{\"id\":\"00000000000040008000000000000001\",\"name\":\"Alex\"}"));
        server.start();

        String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort();
        return MicrosoftAuthConfig.of("test-client-id")
                .withEndpoints(
                        base + "/authorize",
                        base + "/token",
                        base + "/xbl",
                        base + "/xsts",
                        base + "/mclogin",
                        base + "/mcprofile");
    }

    /**
     * Redeems a token against the configured endpoint and returns the rejection it failed with, so a
     * classification assertion reads as one line.
     */
    private static MicrosoftAuthUtil.RefreshRejectedException rejectionFor(MicrosoftAuthConfig config) {
        ExecutionException thrown = assertThrows(
                ExecutionException.class,
                () -> MicrosoftAuthUtil.authenticateWithRefreshToken(config, "still-good")
                        .get());
        return assertInstanceOf(MicrosoftAuthUtil.RefreshRejectedException.class, thrown.getCause());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void sendsRefreshGrantAndReturnsRotatedToken() throws Exception {
        MicrosoftAuthConfig config = startServer();

        MinecraftProfile profile = MicrosoftAuthUtil.authenticateWithRefreshToken(config, "original-refresh")
                .get();

        String body = tokenRequestBody.get();
        assertTrue(body.contains("grant_type=refresh_token"), "must use the refresh grant: " + body);
        assertTrue(body.contains("refresh_token=original-refresh"), "must send the stored token: " + body);
        assertTrue(body.contains("client_id=test-client-id"), "must send the host client id: " + body);
        assertFalse(body.contains("grant_type=authorization_code"), "must not use the code grant: " + body);

        assertEquals("Alex", profile.username());
        assertEquals("00000000-0000-4000-8000-000000000001", profile.uuid());
        assertEquals("mc-access", profile.accessToken());
        assertEquals("rotated", profile.refreshToken(), "the rotated token must survive, not the original");

        // The Minecraft token lives ~24h and the Microsoft OAuth token ~1h. The stamped expiry must
        // describe the token actually stored on the account, which is the Minecraft one.
        long fromNow = profile.expiresAt() - System.currentTimeMillis();
        assertTrue(
                fromNow > 80_000_000L, "expiry must come from the Minecraft lifetime, not the OAuth one: " + fromNow);
        assertTrue(fromNow <= 86_400_000L, "expiry must not exceed the advertised lifetime: " + fromNow);
    }

    @Test
    void keepsTheOriginalTokenWhenMicrosoftOmitsARotation() throws Exception {
        tokenBody = "{\"access_token\":\"ms-access\",\"expires_in\":3600}";
        MicrosoftAuthConfig config = startServer();

        MinecraftProfile profile = MicrosoftAuthUtil.authenticateWithRefreshToken(config, "original-refresh")
                .get();

        assertEquals("original-refresh", profile.refreshToken(), "omitted rotation must not clear the token");
    }

    @Test
    void clientErrorIsAPermanentRejection() throws Exception {
        tokenStatus = 400;
        tokenBody = "{\"error\":\"invalid_grant\"}";
        MicrosoftAuthConfig config = startServer();

        ExecutionException thrown = assertThrows(
                ExecutionException.class,
                () -> MicrosoftAuthUtil.authenticateWithRefreshToken(config, "revoked")
                        .get());

        MicrosoftAuthUtil.RefreshRejectedException rejection =
                assertInstanceOf(MicrosoftAuthUtil.RefreshRejectedException.class, thrown.getCause());
        assertTrue(rejection.permanent(), "a 4xx means the token will never work again");
    }

    @Test
    void throttlingIsTransientEvenWhenTheBodyClaimsAnInvalidGrant() throws Exception {
        tokenStatus = 429;
        tokenBody = "{\"error\":\"invalid_grant\"}";
        MicrosoftAuthConfig config = startServer();

        assertFalse(
                rejectionFor(config).permanent(),
                "a rate limit is the service asking to wait, never a reason to destroy a credential");
    }

    @Test
    void requestTimeoutIsTransient() throws Exception {
        tokenStatus = 408;
        tokenBody = "{\"error\":\"invalid_grant\"}";
        MicrosoftAuthConfig config = startServer();

        assertFalse(rejectionFor(config).permanent(), "a request timeout is retryable, not a spent token");
    }

    @Test
    void hostMisconfigurationDoesNotDestroyTheCredential() throws Exception {
        tokenStatus = 400;
        tokenBody = "{\"error\":\"invalid_client\"}";
        MicrosoftAuthConfig config = startServer();

        assertFalse(
                rejectionFor(config).permanent(),
                "a wrong client id is the host's mistake; the user's refresh token is still good");
    }

    @Test
    void anUnreadableClientErrorIsTransient() throws Exception {
        tokenStatus = 400;
        tokenBody = "not json at all";
        MicrosoftAuthConfig config = startServer();

        assertFalse(rejectionFor(config).permanent(), "only a stated invalid_grant proves the token is spent");
    }

    @Test
    void serverErrorIsATransientRejection() throws Exception {
        tokenStatus = 503;
        tokenBody = "unavailable";
        MicrosoftAuthConfig config = startServer();

        ExecutionException thrown = assertThrows(
                ExecutionException.class,
                () -> MicrosoftAuthUtil.authenticateWithRefreshToken(config, "fine")
                        .get());

        MicrosoftAuthUtil.RefreshRejectedException rejection =
                assertInstanceOf(MicrosoftAuthUtil.RefreshRejectedException.class, thrown.getCause());
        assertFalse(rejection.permanent(), "a 5xx must not cost the user their refresh token");
    }
}
