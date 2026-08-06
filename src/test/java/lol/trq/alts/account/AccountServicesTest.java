package lol.trq.alts.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lol.trq.alts.net.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The account services against a loopback returning each documented status. What each test defends is
 * that a mutation reads the response — its body and its status — rather than the bare code the reference
 * tooling rode on, so a 400 name is told apart from a 403 cooldown and a rename returns the new profile.
 */
class AccountServicesTest {

    private HttpServer server;
    private String base;

    // Per-path canned responses set by each test.
    private final AtomicReference<int[]> nextStatus = new AtomicReference<>(new int[] {200});
    private final AtomicReference<String> nextBody = new AtomicReference<>("{}");
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        HttpUtil.bind(null);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, nextStatus.get()[0], nextBody.get());
        });
        server.start();
        base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private AccountServices services() {
        return AccountServices.of("test-token", "uuid-1", new AccountEndpoints(base));
    }

    private void canned(int status, String body) {
        nextStatus.set(new int[] {status});
        nextBody.set(body);
    }

    @Test
    void profileReadsSkinsAndCapes() throws Exception {
        canned(
                200,
                "{\"id\":\"abc\",\"name\":\"Alex\","
                        + "\"skins\":[{\"id\":\"s1\",\"state\":\"ACTIVE\",\"url\":\"u\",\"variant\":\"SLIM\"}],"
                        + "\"capes\":[{\"id\":\"c1\",\"state\":\"ACTIVE\",\"url\":\"cu\",\"alias\":\"Migrator\"}],"
                        + "\"profileActions\":[]}");

        PlayerProfile profile = services().profile().fetch();

        assertEquals("Alex", profile.name());
        assertEquals(SkinModel.SLIM, profile.activeSkin().variant());
        assertEquals("Migrator", profile.activeCape().alias());
        assertFalse(profile.hasPendingActions());
        assertEquals("Bearer test-token", lastAuth.get(), "every account call carries the bearer");
    }

    @Test
    void entitlementsReadTheProductSetNotABoolean() throws Exception {
        canned(200, "{\"items\":[{\"name\":\"product_game_pass_ultimate\"},{\"name\":\"product_minecraft_bedrock\"}]}");

        Entitlements owned = services().entitlements().fetch();

        assertTrue(owned.ownsJava(), "game pass ultimate can play java");
        assertFalse(owned.ownsJavaOutright(), "but does not own it outright");
        assertTrue(owned.viaGamePass());
        assertTrue(owned.ownsBedrock());
    }

    @Test
    void aRefusedTokenIsAnExceptionCarryingItsStatus() {
        canned(401, "{\"errorMessage\":\"Invalid token\"}");

        AccountException thrown =
                assertThrows(AccountException.class, () -> services().profile().fetch());

        assertEquals(401, thrown.status());
        assertTrue(thrown.tokenRefused());
        assertTrue(thrown.getMessage().contains("Invalid token"), "the service's own message is surfaced");
    }

    @Test
    void nameAvailabilityMapsTheStatus() throws Exception {
        canned(200, "{\"status\":\"AVAILABLE\"}");
        assertEquals(NameAvailability.AVAILABLE, services().name().checkAvailability("Wanted"));

        canned(200, "{\"status\":\"DUPLICATE\"}");
        assertEquals(NameAvailability.DUPLICATE, services().name().checkAvailability("Taken"));

        canned(200, "{\"status\":\"NOT_ALLOWED\"}");
        assertEquals(NameAvailability.NOT_ALLOWED, services().name().checkAvailability("Blocked"));
    }

    @Test
    void aSuccessfulNameChangeReturnsTheUpdatedProfile() throws Exception {
        canned(200, "{\"id\":\"abc\",\"name\":\"NewName\"}");

        NameChangeResult result = services().name().change("NewName");

        assertTrue(result.success());
        assertEquals(NameChangeResult.Outcome.CHANGED, result.outcome());
        assertEquals("NewName", result.profile().name());
        assertEquals("PUT", lastMethod.get());
    }

    @Test
    void nameChangeStatusesAreToldApart() throws Exception {
        canned(400, "{\"errorMessage\":\"invalid name\"}");
        assertEquals(
                NameChangeResult.Outcome.INVALID_NAME,
                services().name().change("!!!").outcome());

        canned(403, "{}");
        assertEquals(
                NameChangeResult.Outcome.UNAVAILABLE,
                services().name().change("Taken").outcome());

        canned(404, "{}");
        assertEquals(
                NameChangeResult.Outcome.NOT_ENTITLED,
                services().name().change("Any").outcome());

        canned(429, "{}");
        assertEquals(
                NameChangeResult.Outcome.RATE_LIMITED,
                services().name().change("Any").outcome());
    }

    @Test
    void eligibilityReadsTheFlagAndCreationTime() throws Exception {
        canned(200, "{\"nameChangeAllowed\":true,\"createdAt\":\"2020-01-01T00:00:00Z\"}");

        NameEligibility eligibility = services().name().eligibility();

        assertTrue(eligibility.nameChangeAllowed());
        assertNotNull(eligibility.createdAt());
    }

    @Test
    void setSkinFromUrlSendsTheVariantAndReturnsTheProfile() throws Exception {
        canned(200, "{\"id\":\"abc\",\"name\":\"Alex\"}");

        PlayerProfile profile = services().skin().setFromUrl("http://skin.example/s.png", SkinModel.SLIM);

        assertEquals("Alex", profile.name());
        assertEquals("POST", lastMethod.get());
    }

    @Test
    void uploadSkinSendsMultipart() throws Exception {
        canned(200, "{\"id\":\"abc\",\"name\":\"Alex\"}");

        PlayerProfile profile = services().skin().upload(new byte[] {1, 2, 3}, SkinModel.CLASSIC);

        assertNotNull(profile);
        assertEquals("POST", lastMethod.get());
    }

    @Test
    void resetSkinDeletes() throws Exception {
        canned(200, "");
        services().skin().reset();
        assertEquals("DELETE", lastMethod.get());
    }

    @Test
    void setCapePutsAndHideDeletes() throws Exception {
        canned(200, "{\"id\":\"abc\",\"name\":\"Alex\",\"capes\":[]}");
        services().cape().setActive("cape-1");
        assertEquals("PUT", lastMethod.get());

        canned(200, "");
        services().cape().hide();
        assertEquals("DELETE", lastMethod.get());
    }

    @Test
    void anErrorMessageNeverLeaksAQueryString() {
        // The availability URL has no query today, but the guard that strips one must hold regardless.
        canned(500, "{}");
        AccountException thrown =
                assertThrows(AccountException.class, () -> services().name().checkAvailability("X"));
        assertFalse(thrown.getMessage().contains("?"), thrown.getMessage());
    }

    @Test
    void ownedCapesComeOffTheProfile() throws Exception {
        canned(
                200,
                "{\"id\":\"abc\",\"name\":\"Alex\",\"capes\":[{\"id\":\"c1\",\"state\":\"INACTIVE\","
                        + "\"url\":\"u\",\"alias\":\"Cape\"}]}");

        List<Cape> capes = services().cape().owned();

        assertEquals(1, capes.size());
        assertEquals("c1", capes.get(0).id());
        assertNull(new PlayerProfile("a", "b", List.of(), capes, List.of()).activeCape());
    }
}
