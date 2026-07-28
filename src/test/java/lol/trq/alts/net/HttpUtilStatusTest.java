package lol.trq.alts.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpUtilStatusTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/token";
    }

    @Test
    void reportsSuccessStatusAndBody() throws Exception {
        String url = startServer(200, "{\"access_token\":\"a\"}");

        HttpUtil.HttpResponse response = HttpUtil.postFormForStatus(url, null, "grant_type=refresh_token");

        assertEquals(200, response.status());
        assertTrue(response.successful());
        assertNotNull(response.body());
        assertEquals("a", response.body().get("access_token").getAsString());
    }

    @Test
    void reportsClientErrorStatusAndParsesErrorBody() throws Exception {
        String url = startServer(400, "{\"error\":\"invalid_grant\"}");

        HttpUtil.HttpResponse response = HttpUtil.postFormForStatus(url, null, "grant_type=refresh_token");

        assertEquals(400, response.status());
        assertFalse(response.successful());
        assertNotNull(response.body(), "the error body must survive so the caller can classify the failure");
        assertEquals("invalid_grant", response.body().get("error").getAsString());
    }

    @Test
    void reportsServerErrorStatus() throws Exception {
        String url = startServer(503, "unavailable");

        HttpUtil.HttpResponse response = HttpUtil.postFormForStatus(url, null, "grant_type=refresh_token");

        assertEquals(503, response.status());
        assertFalse(response.successful());
        assertNull(response.body(), "a non-JSON error body parses to null rather than throwing");
    }
}
