package lol.trq.alts.spi;

/**
 * Sink for the user-facing toast notifications raised during login flows. The host maps {@link Level}
 * onto its own notification styling.
 *
 * @author trq
 * @since 0.1.0
 */
public interface ToastSink {

    /**
     * Severity of a toast, mapped by the host onto its own styles.
     *
     * @author trq
     * @since 0.1.0
     */
    enum Level {
        /** A flow completed successfully. */
        SUCCESS,

        /** A flow failed. */
        ERROR,

        /** Neutral progress or status information. */
        INFO
    }

    /**
     * Publishes a toast.
     *
     * @param level the severity
     * @param title the heading
     * @param description the body text
     * @param durationMs how long to show it, in milliseconds
     */
    void toast(Level level, String title, String description, int durationMs);
}
