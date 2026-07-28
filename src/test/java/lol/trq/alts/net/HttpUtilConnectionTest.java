package lol.trq.alts.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Connection-level guarantees every request shares: it always ends, and it never re-sends its headers. */
class HttpUtilConnectionTest {

    private HttpServer server;
    private final AtomicReference<String> headerSeenByRedirectTarget = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a server whose {@code /start} redirects to {@code /elsewhere}, which records what it got. */
    private String startRedirectingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort();

        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            headerSeenByRedirectTarget.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "{\"id\":\"x\",\"name\":\"Alex\"}");
        });
        server.start();
        return base;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void everyConnectionCarriesFiniteTimeoutsAndRefusesRedirects() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:1/unused").openConnection();

        HttpUtil.applyDefaults(conn);

        assertTrue(
                conn.getConnectTimeout() > 0 && conn.getConnectTimeout() <= 30_000,
                "an unreachable endpoint must fail rather than block: " + conn.getConnectTimeout());
        assertTrue(
                conn.getReadTimeout() > 0 && conn.getReadTimeout() <= 60_000,
                "a silent endpoint must fail rather than park the thread forever: " + conn.getReadTimeout());
        assertFalse(conn.getInstanceFollowRedirects(), "a redirect must never be followed automatically");
    }

    @Test
    void aGetRedirectIsNotFollowedSoTheBearerTokenStaysOnTheOriginalHost() throws Exception {
        String base = startRedirectingServer();

        JsonObject response = HttpUtil.get(base + "/start", Map.of("Authorization", "Bearer secret-token"));

        assertNull(response, "a redirect is not a successful response");
        assertNull(
                headerSeenByRedirectTarget.get(),
                "the Authorization header must not be replayed to whatever a 3xx points at");
    }

    @Test
    void aFormPostRedirectIsNotFollowedEither() throws Exception {
        String base = startRedirectingServer();

        HttpUtil.HttpResponse response = HttpUtil.postFormForStatus(
                base + "/start", Map.of("Authorization", "Bearer secret-token"), "grant_type=refresh_token");

        assertEquals(302, response.status(), "the caller must see the redirect, not silently chase it");
        assertNull(
                headerSeenByRedirectTarget.get(),
                "the Authorization header must not be replayed to whatever a 3xx points at");
    }
}
