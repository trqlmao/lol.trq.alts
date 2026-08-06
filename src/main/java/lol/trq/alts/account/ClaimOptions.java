package lol.trq.alts.account;

import java.time.Duration;
import lol.trq.alts.time.SystemTimeSource;
import lol.trq.alts.time.TimeSource;

/**
 * How a scheduled name claim paces itself around its target instant.
 *
 * @param leadTime how far before the target to begin firing, absorbing clock skew and request latency
 * @param window how long to keep trying after the target before giving up
 * @param attemptSpacing the least gap between two attempts on one worker
 * @param concurrency how many attempt chains run in parallel
 * @param timeSource the clock the schedule reads, for correcting OS drift
 * @author trq
 * @since 1.0.0
 */
public record ClaimOptions(
        Duration leadTime, Duration window, Duration attemptSpacing, int concurrency, TimeSource timeSource) {

    /** Validates the shape and fills a missing time source with the system clock. */
    public ClaimOptions {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1");
        }
        leadTime = nonNegative(leadTime);
        window = window == null || window.isNegative() || window.isZero() ? Duration.ofSeconds(10) : window;
        attemptSpacing = nonNegative(attemptSpacing);
        timeSource = timeSource == null ? new SystemTimeSource() : timeSource;
    }

    /**
     * Returns sensible defaults: begin 300 ms early, try for 10 s, 8 ms between a worker's attempts, three
     * chains, system clock.
     *
     * @return the default options
     * @since 1.0.0
     */
    public static ClaimOptions defaults() {
        return new ClaimOptions(
                Duration.ofMillis(300), Duration.ofSeconds(10), Duration.ofMillis(8), 3, new SystemTimeSource());
    }

    /**
     * Returns a copy reading the given time source.
     *
     * @param value the time source
     * @return a copy with the time source replaced
     * @since 1.0.0
     */
    public ClaimOptions withTimeSource(TimeSource value) {
        return new ClaimOptions(leadTime, window, attemptSpacing, concurrency, value);
    }

    private static Duration nonNegative(Duration value) {
        return value == null || value.isNegative() ? Duration.ZERO : value;
    }
}
