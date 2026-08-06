package lol.trq.alts.bulk;

import java.util.concurrent.CompletableFuture;

/**
 * A running bulk operation.
 *
 * @author trq
 * @since 0.9.0
 */
public interface BulkHandle {

    /**
     * Returns the future holding the report, completed when the run ends however it ends.
     *
     * <p>It completes normally for a cancelled or early-stopped run too — those are outcomes the report
     * describes, not failures of the call.
     *
     * @return the run's report
     * @since 0.9.0
     */
    CompletableFuture<BulkReport> report();

    /**
     * Stops the run from starting any further entries.
     *
     * <p>Entries already in flight are allowed to finish and appear in the report. Abandoning them would
     * leave a half-completed authentication behind — and for an import, an account that was resolved and
     * stored but never reported.
     *
     * @since 0.9.0
     */
    void cancel();
}
