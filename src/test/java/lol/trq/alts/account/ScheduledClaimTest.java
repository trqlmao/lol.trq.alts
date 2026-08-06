package lol.trq.alts.account;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lol.trq.alts.net.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The scheduled claim. Its wait is real-time (a scheduled sleep, then a short spin), so these run against
 * the real clock with small durations rather than a frozen one — a frozen clock would diverge from the
 * real sleep the wait performs. What each test defends: the burst does not fire before its lead time, it
 * stops the instant a change succeeds, and a terminal failure ends it rather than hammering a change that
 * can never land.
 */
class ScheduledClaimTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger status = new AtomicInteger(403);
    private final AtomicInteger changeCalls = new AtomicInteger();
    private final AtomicLong firstCallMillis = new AtomicLong(-1);

    @BeforeEach
    void setUp() throws IOException {
        HttpUtil.bind(null);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                changeCalls.incrementAndGet();
                firstCallMillis.compareAndSet(-1, System.currentTimeMillis());
            }
            exchange.getRequestBody().readAllBytes();
            int code = status.get();
            byte[] body = (code == 200 ? "{\"id\":\"a\",\"name\":\"Wanted\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
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

    private NameService name() {
        return AccountServices.of("token", "uuid", new AccountEndpoints(base)).name();
    }

    @Test
    void noAttemptFiresBeforeTheLeadTime() throws Exception {
        // Target 400ms out, lead 100ms: nothing may fire before ~300ms from now.
        long start = System.currentTimeMillis();
        Instant target = Instant.now().plusMillis(400);
        status.set(200); // succeeds once it fires, so the run ends promptly

        ClaimOptions options =
                new ClaimOptions(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofMillis(5), 1, null);
        ClaimResult result = name().claimAt("Wanted", target, options).get();

        assertTrue(result.claimed(), "the change succeeds once the window opens");
        long firstAt = firstCallMillis.get() - start;
        assertTrue(firstAt >= 250, "no attempt may fire before the lead time; first was at " + firstAt + "ms");
    }

    @Test
    void aSuccessfulChangeStopsTheBurst() throws Exception {
        Instant target = Instant.now(); // already due: fire immediately
        status.set(200);

        ClaimOptions options = new ClaimOptions(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(5), 3, null);
        long start = System.currentTimeMillis();
        ClaimResult result = name().claimAt("Wanted", target, options).get();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.claimed(), "a 200 claims the name");
        assertTrue(result.finalResult().success());
        assertTrue(elapsed < 1_500, "the burst stops on success rather than running the whole window: " + elapsed);
        // Three workers may each fire once before they observe the success; it must not keep going.
        assertTrue(changeCalls.get() <= 4, "the burst stops promptly on success, saw " + changeCalls.get());
    }

    @Test
    void aDeadTokenEndsTheRunRatherThanHammering() throws Exception {
        Instant target = Instant.now();
        status.set(401); // unauthorized: terminal, can never succeed

        ClaimOptions options = new ClaimOptions(Duration.ZERO, Duration.ofSeconds(5), Duration.ofMillis(2), 1, null);
        long start = System.currentTimeMillis();
        ClaimResult result = name().claimAt("Wanted", target, options).get();
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.claimed());
        assertTrue(elapsed < 1_000, "a terminal failure ends the run rather than filling the window: " + elapsed);
        assertTrue(changeCalls.get() <= 2, "a change that can never land is not retried for the window");
    }
}
