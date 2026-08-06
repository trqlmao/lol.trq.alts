package lol.trq.alts.net;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How long to wait before trying again.
 *
 * <p>Two callers want different things from a retry. A service that stated a delay has told you the
 * answer, and guessing a shorter one earns a longer ban; a service that merely failed has told you
 * nothing, so the wait grows and is jittered — fifty accounts retrying in lockstep is the same thundering
 * herd that got them throttled.
 *
 * @author trq
 * @since 0.9.0
 */
public final class Backoff {

    /** Longest wait any delay is clamped to, so a service asking for an hour does not hang a run. */
    public static final Duration MAX_DELAY = Duration.ofMinutes(2);

    private Backoff() {}

    /**
     * Returns how long to wait after a rejected request, preferring what the service asked for.
     *
     * @param response the rejection, or {@code null} when the failure carried no response
     * @param attempt the attempt that just failed, counting from 1
     * @param base the base delay to grow from when the service asked for nothing
     * @return the delay to wait, never longer than {@link #MAX_DELAY}
     * @since 0.9.0
     */
    public static Duration after(HttpUtil.HttpResponse response, int attempt, Duration base) {
        if (response != null && response.retryAfter() != null) {
            return clamp(response.retryAfter());
        }
        return exponential(attempt, base);
    }

    /**
     * Returns a growing, jittered delay.
     *
     * <p>The jitter is the point rather than a refinement: without it every item in a batch that failed
     * together retries at the same instant, which reproduces the burst that caused the failure.
     *
     * @param attempt the attempt that just failed, counting from 1
     * @param base the base delay
     * @return the delay to wait, never longer than {@link #MAX_DELAY}
     * @since 0.9.0
     */
    public static Duration exponential(int attempt, Duration base) {
        if (base == null || base.isNegative() || base.isZero()) {
            return Duration.ZERO;
        }
        int growth = Math.min(Math.max(attempt, 1), 16);
        long millis = base.toMillis() * (1L << (growth - 1));
        long jittered = millis / 2 + ThreadLocalRandom.current().nextLong(millis / 2 + 1);
        return clamp(Duration.ofMillis(jittered));
    }

    /**
     * Clamps a delay to {@link #MAX_DELAY}, treating a negative one as no delay at all.
     *
     * @param delay the delay to clamp
     * @return the clamped delay
     */
    private static Duration clamp(Duration delay) {
        if (delay.isNegative()) {
            return Duration.ZERO;
        }
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }
}
