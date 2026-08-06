package lol.trq.alts.bulk;

import java.time.Duration;

/**
 * How a bulk run should pace itself.
 *
 * @param concurrency how many entries may be in flight at once
 * @param minSpacing the least time between two entries <em>starting</em>
 * @param maxRetries how many times a retryable failure is retried, beyond the first attempt
 * @param retryBaseDelay the delay a retry grows from when the service asked for nothing specific
 * @param stopOnRateLimit whether a stated rate limit ends the run rather than being waited out
 * @author trq
 * @since 0.9.0
 */
public record BulkOptions(
        int concurrency, Duration minSpacing, int maxRetries, Duration retryBaseDelay, boolean stopOnRateLimit) {

    /** Validates the shape, since a zero concurrency would hang the run and a negative delay is a bug. */
    public BulkOptions {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        minSpacing = nonNegative(minSpacing);
        retryBaseDelay = nonNegative(retryBaseDelay);
    }

    /**
     * Returns the defaults: four at a time, a quarter second apart, two retries growing from two
     * seconds, stopping if the service says to slow down.
     *
     * <p>They are deliberately unambitious. The failure this pacing exists to avoid is a source address
     * earning a longer ban, and the cost of being slower than necessary is a sweep that takes another
     * minute.
     *
     * @return the default options
     * @since 0.9.0
     */
    public static BulkOptions defaults() {
        return new BulkOptions(4, Duration.ofMillis(250), 2, Duration.ofSeconds(2), true);
    }

    /**
     * Returns a copy running the given number of entries at once.
     *
     * @param value how many entries may be in flight
     * @return a copy with the concurrency replaced
     * @since 0.9.0
     */
    public BulkOptions withConcurrency(int value) {
        return new BulkOptions(value, minSpacing, maxRetries, retryBaseDelay, stopOnRateLimit);
    }

    /**
     * Returns a copy that keeps going through a stated rate limit, waiting each one out.
     *
     * <p>Worth knowing what that trades: continuing to send to a service that has asked you to stop is
     * how an address earns a longer ban than the one it is already serving.
     *
     * @return a copy that does not stop early
     * @since 0.9.0
     */
    public BulkOptions withoutStoppingOnRateLimit() {
        return new BulkOptions(concurrency, minSpacing, maxRetries, retryBaseDelay, false);
    }

    private static Duration nonNegative(Duration value) {
        return value == null || value.isNegative() ? Duration.ZERO : value;
    }
}
