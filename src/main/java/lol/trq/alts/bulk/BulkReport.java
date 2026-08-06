package lol.trq.alts.bulk;

import java.util.List;

/**
 * What a whole bulk run came to.
 *
 * <p>{@code cancelled} and {@code stoppedEarly} both mean "fewer entries ran than you asked for", and
 * they are kept apart because they call for opposite responses: one is the user's own doing, the other
 * is the service asking to be left alone. Neither is the same as a run where everything ran and some
 * entries failed.
 *
 * @param results one entry per item that ran, in the order they were given
 * @param cancelled whether the run was cancelled before every entry started
 * @param stoppedEarly whether the run stopped itself because the service asked it to slow down
 * @author trq
 * @since 0.9.0
 */
public record BulkReport(List<BulkEntryResult> results, boolean cancelled, boolean stoppedEarly) {

    /** Defensively copies the results, so a report cannot be edited after the fact. */
    public BulkReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * Returns how many entries succeeded.
     *
     * @return the successful count
     * @since 0.9.0
     */
    public int succeeded() {
        return (int) results.stream().filter(BulkEntryResult::success).count();
    }

    /**
     * Returns the entries that failed, for a host listing what needs attention.
     *
     * @return the failed results, in order
     * @since 0.9.0
     */
    public List<BulkEntryResult> failures() {
        return results.stream().filter(result -> !result.success()).toList();
    }

    /**
     * Returns whether every entry that ran succeeded and none were skipped.
     *
     * @return true if the run was complete and clean
     * @since 0.9.0
     */
    public boolean complete() {
        return !cancelled && !stoppedEarly && failures().isEmpty();
    }
}
