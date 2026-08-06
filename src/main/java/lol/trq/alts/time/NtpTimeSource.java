package lol.trq.alts.time;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;

/**
 * A {@link TimeSource} corrected against an NTP server.
 *
 * <p>One clean protocol, one UDP round trip: the offset between the OS clock and the server's is
 * measured and applied, and re-measured on an interval. This is the library's only non-HTTP network
 * shape, and it is deliberately minimal — a single SNTP exchange with the standard four-timestamp offset
 * calculation, not an aggregator over a handful of web time APIs that each answer in a different JSON
 * shape and are slower than the drift they claim to fix.
 *
 * <p>Measurement is lazy and best-effort: the first {@link #now()} after the interval lapses re-syncs,
 * and a failed sync keeps the last good offset (or zero, if none was ever obtained) rather than throwing
 * — a claim should fire on a slightly-off clock rather than not at all.
 *
 * @author trq
 * @since 1.0.0
 */
public final class NtpTimeSource implements TimeSource {

    /** Seconds between the NTP epoch (1900) and the Unix epoch (1970). */
    private static final long NTP_TO_UNIX_SECONDS = 2_208_988_800L;

    private static final int NTP_PORT = 123;
    private static final int NTP_PACKET_SIZE = 48;
    private static final int TIMEOUT_MILLIS = 3_000;

    private final String server;
    private final Duration resyncInterval;

    private volatile long offsetMillis;
    private volatile long lastSyncMillis;
    private volatile boolean everSynced;

    /**
     * Creates a source correcting against {@code pool.ntp.org}, re-syncing every 15 minutes.
     *
     * @since 1.0.0
     */
    public NtpTimeSource() {
        this("pool.ntp.org", Duration.ofMinutes(15));
    }

    /**
     * Creates a source correcting against a given server on a given interval.
     *
     * @param server the NTP server hostname
     * @param resyncInterval how often to re-measure the offset
     * @since 1.0.0
     */
    public NtpTimeSource(String server, Duration resyncInterval) {
        this.server = server;
        this.resyncInterval = resyncInterval;
    }

    @Override
    public Instant now() {
        maybeSync();
        return Instant.ofEpochMilli(System.currentTimeMillis() + offsetMillis);
    }

    /**
     * Forces an offset measurement now, so a caller about to schedule a time-critical claim can sync up
     * front rather than take the correction on the first {@link #now()}.
     *
     * @return true if the sync succeeded
     * @since 1.0.0
     */
    public boolean sync() {
        try {
            offsetMillis = measureOffset();
            everSynced = true;
            lastSyncMillis = System.currentTimeMillis();
            return true;
        } catch (IOException failed) {
            lastSyncMillis = System.currentTimeMillis();
            return false;
        }
    }

    private void maybeSync() {
        long since = System.currentTimeMillis() - lastSyncMillis;
        if (!everSynced || since >= resyncInterval.toMillis()) {
            sync();
        }
    }

    /**
     * Runs one SNTP exchange and returns the offset to add to the local clock, by the standard
     * four-timestamp formula {@code ((receive - originate) + (transmit - destination)) / 2}.
     *
     * @return the offset in milliseconds
     * @throws IOException if the exchange failed
     */
    private long measureOffset() throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            InetAddress address = InetAddress.getByName(server);

            byte[] buffer = new byte[NTP_PACKET_SIZE];
            buffer[0] = 0b00_100_011; // leap 0, version 4, mode 3 (client)

            long originate = System.currentTimeMillis();
            writeTimestamp(buffer, originate);
            socket.send(new DatagramPacket(buffer, buffer.length, address, NTP_PORT));

            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            long destination = System.currentTimeMillis();

            long receive = readTimestamp(buffer, 32);
            long transmit = readTimestamp(buffer, 40);
            return ((receive - originate) + (transmit - destination)) / 2;
        }
    }

    private static void writeTimestamp(byte[] buffer, long millis) {
        long seconds = millis / 1000L + NTP_TO_UNIX_SECONDS;
        long fraction = ((millis % 1000L) * 0x1_0000_0000L) / 1000L;
        for (int i = 3; i >= 0; i--) {
            buffer[40 + i] = (byte) (seconds & 0xff);
            seconds >>>= 8;
        }
        for (int i = 3; i >= 0; i--) {
            buffer[44 + i] = (byte) (fraction & 0xff);
            fraction >>>= 8;
        }
    }

    private static long readTimestamp(byte[] buffer, int offset) {
        long seconds = 0;
        for (int i = 0; i < 4; i++) {
            seconds = (seconds << 8) | (buffer[offset + i] & 0xffL);
        }
        long fraction = 0;
        for (int i = 4; i < 8; i++) {
            fraction = (fraction << 8) | (buffer[offset + i] & 0xffL);
        }
        long millis = (seconds - NTP_TO_UNIX_SECONDS) * 1000L;
        millis += (fraction * 1000L) / 0x1_0000_0000L;
        return millis;
    }
}
