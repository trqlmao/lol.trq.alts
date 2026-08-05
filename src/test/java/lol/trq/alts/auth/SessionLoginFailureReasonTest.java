package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.auth.AltLoginCallback.LoginResult;
import lol.trq.alts.model.LoginMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session route's two ways of failing mean opposite things to a host: a refused token is spent and
 * should send the user to a fresh login, while an unreachable service says nothing about the token and
 * should offer a retry. Collapsing both onto {@code INVALID_TOKEN} makes the documented advice to branch
 * on the reason wrong for the one case where retrying is the right answer.
 */
class SessionLoginFailureReasonTest {

    private static final String OPAQUE_TOKEN = "not-a-jwt-so-the-fast-path-is-skipped";

    @TempDir
    Path vaultDir;

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        AltStoreTestSupport.bindTo(vaultDir);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mcprofile", exchange -> respond(exchange, 401, "{}"));
        server.start();
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

    private static MicrosoftAuthConfig pointingAt(String base) {
        return MicrosoftAuthConfig.of("test-client-id")
                .withEndpoints(
                        base + "/authorize",
                        base + "/token",
                        base + "/xbl",
                        base + "/xsts",
                        base + "/mclogin",
                        base + "/mcprofile");
    }

    private static String loopbackBase(int port) {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port;
    }

    /** Returns a port nothing is listening on, so a request to it is refused rather than answered. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void aRefusedTokenIsInvalidNotANetworkProblem() throws Exception {
        MicrosoftAuthConfig config = pointingAt(loopbackBase(server.getAddress().getPort()));

        LoginResult result = new AltLoginServiceImpl(session -> {}, config)
                .loginSession(OPAQUE_TOKEN, LoginMode.DIRECT)
                .get();

        assertFalse(result.success());
        assertEquals(FailureReason.INVALID_TOKEN, result.reason(), "the service answered, and its answer was no");
    }

    @Test
    void anUnreachableServiceIsANetworkFailure() throws Exception {
        MicrosoftAuthConfig config = pointingAt(loopbackBase(closedPort()));

        LoginResult result = new AltLoginServiceImpl(session -> {}, config)
                .loginSession(OPAQUE_TOKEN, LoginMode.DIRECT)
                .get();

        assertFalse(result.success());
        assertEquals(
                FailureReason.NETWORK,
                result.reason(),
                "a service that never answered proves nothing about the token, so the host must be told to retry");
    }
}
