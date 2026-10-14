package sh.pcx.packmerger.distribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RejectionLimiterTest {

    @Test
    void warnsOncePerPlayerUntilSessionEnds() {
        RejectionLimiter l = new RejectionLimiter();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(l.shouldWarn(a));
        assertFalse(l.shouldWarn(a));
        assertFalse(l.shouldWarn(a));
        assertTrue(l.shouldWarn(b), "another player has their own budget");
        l.forget(a);
        assertTrue(l.shouldWarn(a), "a new session warns again");
        l.clear();
        assertTrue(l.shouldWarn(b));
    }
}
