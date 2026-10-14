package sh.pcx.packmerger.distribution;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.distribution.DeliveryTracker.Action;
import sh.pcx.packmerger.distribution.DeliveryTracker.Status;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryTrackerTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void failedDownloadIsResentUpToTheCapThenGivesUp() {
        DeliveryTracker t = new DeliveryTracker(3);
        for (int attempt = 1; attempt <= 3; attempt++) {
            var d = t.onStatus(alice, Status.FAILED_DOWNLOAD);
            assertEquals(Action.RESEND, d.action());
            assertEquals(attempt, d.attempt());
            t.resendFinished(alice); // the scheduled resend fired
        }
        var d = t.onStatus(alice, Status.FAILED_DOWNLOAD);
        assertEquals(Action.GAVE_UP, d.action());
        assertEquals(3, d.attempt());
        // The cap holds for the rest of the session.
        assertEquals(Action.GAVE_UP, t.onStatus(alice, Status.DISCARDED).action());
    }

    @Test
    void discardedCountsLikeAFailedDownload() {
        DeliveryTracker t = new DeliveryTracker(1);
        assertEquals(Action.RESEND, t.onStatus(alice, Status.DISCARDED).action());
        t.resendFinished(alice);
        assertEquals(Action.GAVE_UP, t.onStatus(alice, Status.FAILED_DOWNLOAD).action());
    }

    @Test
    void duplicateFailureWhileAResendIsPendingIsIgnored() {
        DeliveryTracker t = new DeliveryTracker(3);
        assertEquals(Action.RESEND, t.onStatus(alice, Status.FAILED_DOWNLOAD).action());
        assertEquals(Action.RESEND_ALREADY_PENDING, t.onStatus(alice, Status.DISCARDED).action());
        assertEquals(1, t.attempts(alice));
    }

    @Test
    void zeroAttemptsDisablesSelfHeal() {
        DeliveryTracker t = new DeliveryTracker(0);
        assertEquals(Action.GAVE_UP, t.onStatus(alice, Status.FAILED_DOWNLOAD).action());
    }

    @Test
    void invalidUrlAndFailedReloadAreNeverRetried() {
        DeliveryTracker t = new DeliveryTracker(3);
        assertEquals(Action.INVALID_URL, t.onStatus(alice, Status.INVALID_URL).action());
        assertEquals(Action.FAILED_RELOAD, t.onStatus(alice, Status.FAILED_RELOAD).action());
        assertEquals(0, t.attempts(alice));
    }

    @Test
    void successResetsTheCounterAndJoinStartsAFreshSession() {
        DeliveryTracker t = new DeliveryTracker(2);
        t.onStatus(alice, Status.FAILED_DOWNLOAD);
        t.resendFinished(alice);
        assertEquals(Action.LOADED, t.onStatus(alice, Status.SUCCESSFULLY_LOADED).action());
        assertEquals(0, t.attempts(alice));

        t.onStatus(alice, Status.FAILED_DOWNLOAD);
        t.resendFinished(alice);
        t.onStatus(alice, Status.FAILED_DOWNLOAD);
        t.resendFinished(alice);
        assertEquals(Action.GAVE_UP, t.onStatus(alice, Status.FAILED_DOWNLOAD).action());
        t.onJoin(alice);
        assertEquals(Action.RESEND, t.onStatus(alice, Status.FAILED_DOWNLOAD).action());
    }

    @Test
    void inProgressStatusesNeedNoAction() {
        DeliveryTracker t = new DeliveryTracker(3);
        assertEquals(Action.NONE, t.onStatus(alice, Status.ACCEPTED).action());
        assertEquals(Action.NONE, t.onStatus(alice, Status.DOWNLOADED).action());
        assertEquals(Action.DECLINED, t.onStatus(alice, Status.DECLINED).action());
    }

    @Test
    void awaitingPlayersAreDrainedOnceAndForgottenOnQuitOrSend() {
        DeliveryTracker t = new DeliveryTracker(3);
        t.markAwaiting(alice);
        t.markAwaiting(bob);
        t.onQuit(bob);
        assertTrue(t.isAwaiting(alice));
        assertEquals(List.of(alice), t.drainAwaiting());
        assertTrue(t.drainAwaiting().isEmpty());

        t.markAwaiting(alice);
        t.onSent(alice);
        assertTrue(t.drainAwaiting().isEmpty());
    }

    @Test
    void capCanBeChangedOnReload() {
        DeliveryTracker t = new DeliveryTracker(3);
        t.setMaxAttempts(1);
        assertEquals(Action.RESEND, t.onStatus(alice, Status.FAILED_DOWNLOAD).action());
        t.resendFinished(alice);
        assertEquals(Action.GAVE_UP, t.onStatus(alice, Status.FAILED_DOWNLOAD).action());
        t.setMaxAttempts(-5);
        assertEquals(0, t.getMaxAttempts());
    }

    @Test
    void unknownStatusNamesMapToOther() {
        assertEquals(Status.FAILED_DOWNLOAD, Status.of("FAILED_DOWNLOAD"));
        assertEquals(Status.OTHER, Status.of("SOMETHING_NEW"));
        assertEquals(Status.OTHER, Status.of(null));
    }
}
