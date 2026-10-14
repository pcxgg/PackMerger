package sh.pcx.packmerger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MergeRequestStateTest {

    @Test
    void startupMergeClearsPendingWhenItRuns() {
        MergeRequestState s = new MergeRequestState();
        s.startupScheduled();
        assertTrue(s.isStartupPending());
        s.mergeStarted();
        assertFalse(s.isStartupPending());
        assertFalse(s.takeDeferred());
    }

    @Test
    void startupMergeBlockedByRunningMergeDoesNotStayPendingForever() {
        MergeRequestState s = new MergeRequestState();
        s.startupScheduled();
        s.mergeBlockedByRunning();
        assertFalse(s.isStartupPending(), "API requests must be honoured again");
        assertTrue(s.takeDeferred(), "a follow-up merge is owed");
        assertFalse(s.takeDeferred());
    }

    @Test
    void blockedMergeAlwaysOwesAFollowUp() {
        MergeRequestState s = new MergeRequestState();
        s.mergeBlockedByRunning();
        assertTrue(s.takeDeferred());
        assertFalse(s.takeDeferred());
    }

    @Test
    void requestBlockedWhileTheRunningMergeIsFinishingIsNotLost() {
        MergeRequestState s = new MergeRequestState();
        s.mergeStarted();
        // The delayed task's attempt loses the lock race (the running merge has not released it yet,
        // which happens before its finally block calls takeDeferred())...
        s.mergeBlockedByRunning();
        // ...so the finally block sees the request and runs it.
        assertTrue(s.takeDeferred());
        // A merge that starts afterwards owes nothing.
        s.mergeStarted();
        assertFalse(s.takeDeferred());
    }

    @Test
    void requestDuringMergeIsRunOnce() {
        MergeRequestState s = new MergeRequestState();
        s.mergeBlockedByRunning();
        assertTrue(s.takeDeferred());
        assertFalse(s.takeDeferred());
    }
}
