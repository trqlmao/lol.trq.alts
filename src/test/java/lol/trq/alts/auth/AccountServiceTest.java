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
import java.util.concurrent.atomic.AtomicInteger;
import lol.trq.alts.AltsRuntime;
import lol.trq.alts.auth.AccountStatus.State;
import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.BanInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Operating on an account without logging into it. The property every test here defends is that the live
 * session does not move: a host sweeping a hundred alts must not switch session a hundred times, and that
 * is exactly what building the sweep on {@code loginAccount} would do.
 */
class AccountServiceTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String UUID = "00000000-0000-4000-8000-000000000001";
    private static final String PROFILE_BODY = "{\"id\":\"00000000000040008000000000000001\",\"name\":\"Alex\"}";

    @TempDir
    Path vaultDir;

    private HttpServer server;
    private MicrosoftAuthConfig config;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger profileCalls = new AtomicInteger();

    private int profileStatus = 200;

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
        server.createContext("/xbl", exchange -> respond(exchange, 200, "{\"Token\":\"xbl\"}"));
        server.createContext(
                "/xsts",
                exchange -> respond(
                        exchange, 200, "{\"Token\":\"xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}"));
        server.createContext(
                "/mclogin",
                exchange -> respond(exchange, 200, "{\"access_token\":\"mc-access\",\"expires_in\":86400}"));
        server.createContext("/mcprofile", exchange -> {
            profileCalls.incrementAndGet();
            respond(exchange, profileStatus, profileStatus == 200 ? PROFILE_BODY : "{}");
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

    private AltAccountServiceImpl service() {
        return new AltAccountServiceImpl(config, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    private static AltAccount renewable(long expiresAt) {
        return AltAccount.of(UUID, "Alex", "stored-access", AccountType.MICROSOFT)
                .withTokens("stored-access", "stored-refresh", expiresAt);
    }

    private static AltAccount unrenewable(long expiresAt) {
        return AltAccount.of(UUID, "Alex", "stored-access", AccountType.SESSION)
                .withTokens("stored-access", null, expiresAt);
    }

    @Test
    void aLiveTokenChecksOutValid() throws Exception {
        AccountStatus status = service().check(renewable(NOW + 3_600_000L)).get();

        assertEquals(State.VALID, status.state());
        assertTrue(status.usable());
        assertEquals(1, profileCalls.get(), "check asks the service, that is what it is for");
    }

    /**
     * The reason {@code check} exists apart from {@code refresh}: the token endpoint rotates the refresh
     * token on every redemption, so a sweep built on renewal would spend one rotation per account per
     * sweep, for accounts that were merely being looked at.
     */
    @Test
    void checkNeverSpendsARotation() throws Exception {
        profileStatus = 401;
        AltAccount account = renewable(NOW - 1);
        AltStoreTestSupport.seed(account);

        AccountStatus status = service().check(account).get();

        assertEquals(State.EXPIRED, status.state(), "a refused token on a renewable account is recoverable");
        assertEquals(FailureReason.INVALID_TOKEN, status.reason());
        assertEquals(0, tokenCalls.get(), "check must never redeem the refresh token");
        assertEquals("stored-refresh", AltStoreTestSupport.find(UUID).refreshToken(), "check changes nothing at rest");
    }

    @Test
    void aRefusedTokenWithNothingToRenewFromNeedsAFreshLogin() throws Exception {
        profileStatus = 401;

        AccountStatus status = service().check(unrenewable(NOW - 1)).get();

        assertEquals(State.REAUTH_REQUIRED, status.state());
        assertEquals(FailureReason.INVALID_TOKEN, status.reason());
    }

    /**
     * A 404 from the profile endpoint means the credentials authenticated and there is simply no
     * Minecraft profile behind them. Renewing cannot help, and reporting it as a bad token would send the
     * user through a browser login that resolves nothing.
     */
    @Test
    void anAccountWithNoMinecraftProfileIsNotEntitled() throws Exception {
        profileStatus = 404;
        AltAccount account = renewable(NOW + 3_600_000L);
        AltStoreTestSupport.seed(account);

        AccountStatus status = service().refresh(account).get();

        assertEquals(State.NOT_ENTITLED, status.state());
        assertEquals(FailureReason.NOT_ENTITLED, status.reason());
        assertEquals(0, tokenCalls.get(), "no amount of renewal produces a profile that does not exist");
        assertEquals(
                "stored-refresh",
                AltStoreTestSupport.find(UUID).refreshToken(),
                "a missing profile says nothing about the credential");
    }

    @Test
    void refreshRenewsAnExpiredAccountAndPersistsTheRotation() throws Exception {
        AltAccount account = renewable(NOW - 1).withBan("serverone", BanInfo.observed("self", "x"));
        AltStoreTestSupport.seed(account);

        AccountStatus status = service().refresh(account).get();

        assertEquals(State.RENEWED, status.state());
        assertTrue(status.usable());
        assertEquals(1, tokenCalls.get(), "renewal happens exactly once, never in a loop");
        assertEquals("rotated", status.account().refreshToken(), "the caller gets the credential it now holds");
        assertEquals("mc-access", status.account().accessToken());
        assertTrue(status.account().banned("serverone"), "renewal preserves what the store knew");

        AltAccount persisted = AltStoreTestSupport.reloadFromDiskAndFind(UUID);
        assertEquals("rotated", persisted.refreshToken(), "the rotation must survive a restart");
    }

    @Test
    void refreshTakesALiveUnrenewableTokenAtItsWordWithoutAskingAnyone() throws Exception {
        AccountStatus status = service().refresh(unrenewable(NOW + 3_600_000L)).get();

        assertEquals(State.VALID, status.state());
        assertEquals(0, profileCalls.get(), "refreshing must not be more expensive than logging in was");
        assertEquals(0, tokenCalls.get());
    }

    @Test
    void anOfflineAccountIsAlwaysUsableAndNeverAskedAbout() throws Exception {
        AltAccount offline = AltAccount.of(UUID, "Steve", "", AccountType.OFFLINE);

        assertTrue(service().check(offline).get().usable());
        assertTrue(service().refresh(offline).get().usable());
        assertEquals(0, profileCalls.get(), "an offline account has nothing to validate against");
    }

    @Test
    void anUnreachableServiceIsRetryableAndCostsNothing() throws Exception {
        AltAccount account = renewable(NOW + 3_600_000L);
        AltStoreTestSupport.seed(account);
        server.stop(0);
        server = null;

        AccountStatus status = service().check(account).get();

        assertEquals(State.UNREACHABLE, status.state());
        assertEquals(FailureReason.NETWORK, status.reason());
        assertEquals(
                "stored-refresh",
                AltStoreTestSupport.find(UUID).refreshToken(),
                "an outage must not cost the user their credential");
    }

    @Test
    void aPermanentlyRejectedRenewalDiscardsTheSpentCredential() throws Exception {
        server.removeContext("/token");
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
        });
        AltAccount account = renewable(NOW - 1);
        AltStoreTestSupport.seed(account);

        AccountStatus status = service().refresh(account).get();

        assertEquals(State.REAUTH_REQUIRED, status.state());
        assertEquals(FailureReason.REAUTH_REQUIRED, status.reason());
        assertNull(status.account().refreshToken(), "the status must not hand back a token that is gone");
        assertNull(AltStoreTestSupport.find(UUID).refreshToken(), "a spent credential must not stay at rest");
    }

    /**
     * The whole point of the surface, asserted end to end through the runtime rather than against the
     * implementation class: neither operation may install a session.
     */
    @Test
    void neitherCheckNorRefreshTouchesTheLiveSession() throws Exception {
        AltsRuntime<Object> alts = new AltsRuntime.Builder<Object>()
                .sessionInjector(session -> {
                    throw new AssertionError("operating on an account must never install a session");
                })
                .vaultDirectory(() -> vaultDir)
                .mainThread(Runnable::run)
                .toastSink((level, title, description, durationMs) -> {})
                .microsoftAuth(config)
                .build();

        AltAccount expired = renewable(NOW - 1);
        AltStoreTestSupport.seed(expired);

        assertTrue(alts.accountService().check(expired).get() != null);
        assertTrue(alts.accountService().refresh(expired).get().usable());
        assertFalse(
                AltStoreTestSupport.find(UUID).accessToken().isBlank(),
                "the account was still refreshed, it just was not logged into");
    }
}
