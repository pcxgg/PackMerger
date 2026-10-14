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
    void blockedMergeAfterStartupOwesNothing() {
        MergeRequestState s = new MergeRequestState();
        s.mergeBlockedByRunning();
        assertFalse(s.takeDeferred());
    }

    @Test
    void requestDuringMergeIsRunOnce() {
        MergeRequestState s = new MergeRequestState();
        s.deferRequest();
        assertTrue(s.takeDeferred());
        assertFalse(s.takeDeferred());
    }
}
