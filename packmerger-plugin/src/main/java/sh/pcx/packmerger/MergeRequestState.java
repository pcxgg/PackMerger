package sh.pcx.packmerger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bookkeeping for API-triggered merges: whether the delayed startup merge is still
 * outstanding (requests are then left to it) and whether a request arrived that must
 * run once the current merge ends.
 */
final class MergeRequestState {

    private volatile boolean startupPending;
    private final AtomicBoolean requested = new AtomicBoolean(false);

    void startupScheduled() { startupPending = true; }

    boolean isStartupPending() { return startupPending; }

    /** A merge claimed the lock: the startup merge is no longer outstanding. */
    void mergeStarted() {
        startupPending = false;
        requested.set(false);
    }

    /** A merge was attempted but another one holds the lock; that one covers startup. */
    void mergeBlockedByRunning() {
        if (startupPending) {
            startupPending = false;
            requested.set(true);
        }
    }

    /** A request fired while a merge was running. */
    void deferRequest() { requested.set(true); }

    /** @return {@code true} (once) if a deferred request must run now */
    boolean takeDeferred() { return requested.getAndSet(false); }
}
