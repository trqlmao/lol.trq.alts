package lol.trq.alts.time;

import java.time.Instant;

/**
 * The default {@link TimeSource}: the OS clock, uncorrected. Fine for a claim whose target is not
 * millisecond-critical; a claim that needs the sub-second edge wants {@link NtpTimeSource} instead.
 *
 * @author trq
 * @since 1.0.0
 */
public final class SystemTimeSource implements TimeSource {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
