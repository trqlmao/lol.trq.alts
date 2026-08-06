package lol.trq.alts.time;

import java.time.Instant;

/**
 * The current instant, corrected however the host likes.
 *
 * <p>A scheduled name claim fires at a target time, and the OS clock is off by enough to miss it — tens
 * to hundreds of milliseconds is common. This seam lets a host feed corrected time in: the default
 * {@link SystemTimeSource} trusts the OS clock, {@link NtpTimeSource} corrects against an NTP server, and
 * a host that already holds a corrected clock plugs its own in and the library opens no socket for it.
 *
 * @author trq
 * @since 1.0.0
 */
@FunctionalInterface
public interface TimeSource {

    /**
     * Returns the current instant.
     *
     * @return now, corrected
     * @since 1.0.0
     */
    Instant now();
}
