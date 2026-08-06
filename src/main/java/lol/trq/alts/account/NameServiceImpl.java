package lol.trq.alts.account;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lol.trq.alts.net.HttpUtil;
import lol.trq.alts.net.NetworkScope;
import lol.trq.alts.time.TimeSource;

/**
 * Concrete {@link NameService}. The reads and the single change are ordinary bearer calls; the scheduled
 * claim borrows the bulk runner's structural concurrency — worker chains over the common pool, first
 * success cancels the rest, no executor created — and adds a two-phase wait that sleeps coarsely then
 * spins briefly, because a scheduler sleep overshoots a millisecond target.
 *
 * @author trq
 * @since 1.0.0
 */
final class NameServiceImpl implements NameService {

    /** Below this much remaining, the wait switches from sleeping to a bounded spin for accuracy. */
    private static final long SPIN_THRESHOLD_MILLIS = 20L;

    private final AccountHttp http;
    private final AccountEndpoints endpoints;
    private final Gson gson;

    NameServiceImpl(AccountHttp http, AccountEndpoints endpoints, Gson gson) {
        this.http = http;
        this.endpoints = endpoints;
        this.gson = gson;
    }

    @Override
    public NameAvailability checkAvailability(String name) throws AccountException {
        JsonObject body = http.get(endpoints.nameAvailability(name), NetworkScope.Purpose.PROFILE);
        return NameAvailability.from(body.has("status") ? body.get("status").getAsString() : null);
    }

    @Override
    public NameEligibility eligibility() throws AccountException {
        JsonObject body = http.get(endpoints.nameChangeInfo(), NetworkScope.Purpose.PROFILE);
        boolean allowed =
                body.has("nameChangeAllowed") && body.get("nameChangeAllowed").getAsBoolean();
        Instant created =
                parseInstant(body.has("createdAt") ? body.get("createdAt").getAsString() : null);
        return new NameEligibility(allowed, created);
    }

    @Override
    public NameChangeResult change(String name) throws AccountException {
        HttpUtil.HttpResponse response;
        try {
            response = HttpUtil.sendForStatus(
                    "PUT",
                    endpoints.nameChange(name),
                    null,
                    java.util.Map.of("Authorization", "Bearer " + http.token()),
                    null,
                    NetworkScope.forAccount(NetworkScope.Purpose.PROFILE, http.accountUuid(), name));
        } catch (Exception transport) {
            throw new AccountException("PUT name change: " + transport.getMessage(), transport);
        }
        return classify(response);
    }

    /**
     * Maps a name-change response onto a result, reading the error body so the message says why.
     *
     * @param response the change response
     * @return the classified result
     */
    private NameChangeResult classify(HttpUtil.HttpResponse response) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            PlayerProfile profile =
                    response.body() == null ? null : gson.fromJson(response.body(), PlayerProfile.class);
            return new NameChangeResult(NameChangeResult.Outcome.CHANGED, profile, "Name changed", null);
        }
        NameChangeResult.Outcome outcome =
                switch (status) {
                    case 400 -> NameChangeResult.Outcome.INVALID_NAME;
                    case 401 -> NameChangeResult.Outcome.UNAUTHORIZED;
                    case 403 -> NameChangeResult.Outcome.UNAVAILABLE;
                    case 404 -> NameChangeResult.Outcome.NOT_ENTITLED;
                    case 429 -> NameChangeResult.Outcome.RATE_LIMITED;
                    default -> NameChangeResult.Outcome.FAILED;
                };
        return new NameChangeResult(outcome, null, message(response, outcome), response.retryAfter());
    }

    private static String message(HttpUtil.HttpResponse response, NameChangeResult.Outcome outcome) {
        JsonObject body = response.body();
        if (body != null) {
            for (String field : new String[] {"errorMessage", "error", "details"}) {
                if (body.has(field) && body.get(field).isJsonPrimitive()) {
                    return body.get(field).getAsString();
                }
            }
        }
        return outcome + " (status " + response.status() + ")";
    }

    @Override
    public CompletableFuture<ClaimResult> claimAt(String name, Instant target, ClaimOptions options) {
        ClaimOptions opts = options == null ? ClaimOptions.defaults() : options;
        Instant begin = target.minus(opts.leadTime());
        Instant deadline = target.plus(opts.window());

        AtomicBoolean claimed = new AtomicBoolean();
        // Distinct from claimed: a terminal failure (dead token, no entitlement) stops the run without
        // the name having been claimed, so the two must not share a flag.
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<NameChangeResult> last = new AtomicReference<>();

        return waitUntil(begin, opts.timeSource()).thenCompose(ignored -> {
            java.util.List<CompletableFuture<Void>> workers = new java.util.ArrayList<>(opts.concurrency());
            for (int i = 0; i < opts.concurrency(); i++) {
                workers.add(worker(name, deadline, opts, claimed, stop, attempts, last));
            }
            return CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new))
                    .thenApply(done -> new ClaimResult(claimed.get(), attempts.get(), last.get()));
        });
    }

    /**
     * One attempt chain: try the change, and if it neither ended the run nor ran out of time, wait the
     * spacing and try again.
     */
    private CompletableFuture<Void> worker(
            String name,
            Instant deadline,
            ClaimOptions opts,
            AtomicBoolean claimed,
            AtomicBoolean stop,
            AtomicInteger attempts,
            AtomicReference<NameChangeResult> last) {
        if (stop.get() || opts.timeSource().now().isAfter(deadline)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> attemptChange(name)).thenCompose(result -> {
            attempts.incrementAndGet();
            last.set(result);
            if (result.success()) {
                claimed.set(true);
                stop.set(true);
                return CompletableFuture.completedFuture((Void) null);
            }
            // A dead token or an unentitled account will never succeed; stop the whole run rather than
            // hammer a change that cannot land. The name was not claimed, so only `stop` is set.
            if (result.outcome() == NameChangeResult.Outcome.UNAUTHORIZED
                    || result.outcome() == NameChangeResult.Outcome.NOT_ENTITLED) {
                stop.set(true);
                return CompletableFuture.completedFuture((Void) null);
            }
            return delay(opts.attemptSpacing())
                    .thenCompose(ignored -> worker(name, deadline, opts, claimed, stop, attempts, last));
        });
    }

    /**
     * Runs one change attempt, flattening a thrown {@link AccountException} into a failed result so a
     * transport blip does not abort the whole burst.
     */
    private NameChangeResult attemptChange(String name) {
        try {
            return change(name);
        } catch (AccountException failed) {
            return new NameChangeResult(NameChangeResult.Outcome.FAILED, null, failed.getMessage(), null);
        }
    }

    /**
     * Waits until {@code target}, sleeping coarsely down to {@link #SPIN_THRESHOLD_MILLIS} and then
     * spinning the final stretch, because a scheduler sleep overshoots a millisecond deadline. The spin
     * is bounded by that threshold so it never becomes a core-pinning busy loop.
     */
    private static CompletableFuture<Void> waitUntil(Instant target, TimeSource clock) {
        long remaining = Duration.between(clock.now(), target).toMillis();
        if (remaining <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        if (remaining <= SPIN_THRESHOLD_MILLIS) {
            return CompletableFuture.supplyAsync(() -> {
                while (clock.now().isBefore(target)) {
                    Thread.onSpinWait();
                }
                return null;
            });
        }
        long sleep = remaining - SPIN_THRESHOLD_MILLIS;
        return delay(Duration.ofMillis(sleep)).thenCompose(ignored -> waitUntil(target, clock));
    }

    private static CompletableFuture<Void> delay(Duration duration) {
        long millis = duration == null ? 0L : duration.toMillis();
        if (millis <= 0L) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(
                        () -> null, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS))
                .thenApply(ignored -> null);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notIso) {
            return null;
        }
    }
}
