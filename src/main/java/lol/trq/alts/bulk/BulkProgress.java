package lol.trq.alts.bulk;

/**
 * Where a bulk run has got to.
 *
 * <p>Callbacks arrive on whichever pool thread the entry finished on, so a host that draws from them
 * marshals onto its own thread first.
 *
 * <p>Reported per entry rather than as a percentage on purpose: a bar tells a user how long to wait,
 * while the thing they actually need to know is <em>which</em> alt failed and why.
 *
 * @author trq
 * @since 0.9.0
 */
public interface BulkProgress {

    /** A progress sink that discards everything, for a caller that only wants the report. */
    BulkProgress NONE = new BulkProgress() {
        @Override
        public void started(int index, int total, String label) {}

        @Override
        public void completed(int index, int total, BulkEntryResult result) {}
    };

    /**
     * Called as an entry begins.
     *
     * @param index the entry's position, counting from 0
     * @param total how many entries the run was given
     * @param label a name safe to display, never the input line
     * @since 0.9.0
     */
    void started(int index, int total, String label);

    /**
     * Called as an entry finishes, whether it succeeded or not.
     *
     * @param index the entry's position, counting from 0
     * @param total how many entries the run was given
     * @param result what became of it
     * @since 0.9.0
     */
    void completed(int index, int total, BulkEntryResult result);

    /**
     * Called once when the run is over, however it ended.
     *
     * @param report what the run came to
     * @since 0.9.0
     */
    default void finished(BulkReport report) {}
}
