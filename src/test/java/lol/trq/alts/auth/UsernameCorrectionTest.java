package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import lol.trq.alts.auth.AccountStatus.State;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A validation already asks the profile endpoint, whose answer carries the current username. When a
 * player has renamed, the stored record should pick that up rather than keep showing the name it had
 * when it was saved.
 */
class UsernameCorrectionTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String UUID = "00000000-0000-4000-8000-000000000001";

    @TempDir
    Path vaultDir;

    private HttpServer server;
    private MicrosoftAuthConfig config;
    private volatile String currentName = "NewName";

    @BeforeEach
    void setUp() throws IOException {
        AltStoreTestSupport.bindTo(vaultDir);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/mcprofile",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"00000000000040008000000000000001\",\"name\":\"" + currentName + "\"}"));
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

    private AltAccountServiceImpl service() {
        return new AltAccountServiceImpl(config, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void aRenamedAccountHasItsStoredUsernameCorrected() throws Exception {
        AltAccount stored = AltAccount.of(UUID, "OldName", "stored-access", AccountType.MICROSOFT)
                .withTokens("stored-access", "stored-refresh", NOW + 3_600_000L);
        AltStoreTestSupport.seed(stored);

        AccountStatus status = service().check(stored).get();

        assertEquals(State.VALID, status.state());
        assertEquals("NewName", status.account().username(), "the caller gets the current name back");
        assertEquals(
                "NewName",
                AltStoreTestSupport.reloadFromDiskAndFind(UUID).username(),
                "and the correction reaches disk, not just the returned copy");
    }

    @Test
    void anUnchangedNameWritesNothing() throws Exception {
        currentName = "SameName";
        AltAccount stored = AltAccount.of(UUID, "SameName", "stored-access", AccountType.MICROSOFT)
                .withTokens("stored-access", "stored-refresh", NOW + 3_600_000L);
        AltStoreTestSupport.seed(stored);

        AccountStatus status = service().check(stored).get();

        assertEquals("SameName", status.account().username());
    }
}
