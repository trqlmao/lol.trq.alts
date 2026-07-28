package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountNetworkUtilTest {

    private HttpServer server;
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/profile", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/profile";
    }

    @Test
    void resolvesProfileFromTheSuppliedEndpoint() throws Exception {
        String url = startServer(200, "{\"id\":\"abc\",\"name\":\"Alex\"}");

        String[] profile = AccountNetworkUtil.fetchProfileFromToken("tok", url);

        assertArrayEquals(new String[] {"Alex", "abc"}, profile);
        assertEquals("Bearer tok", seenAuthorization.get());
    }

    @Test
    void returnsNullWhenTheTokenIsRejected() throws Exception {
        String url = startServer(401, "{}");

        assertNull(AccountNetworkUtil.fetchProfileFromToken("expired", url));
    }
}
