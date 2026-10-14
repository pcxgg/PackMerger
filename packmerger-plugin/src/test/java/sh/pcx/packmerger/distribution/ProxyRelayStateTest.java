package sh.pcx.packmerger.distribution;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ProxyRelayStateTest {

    private static final byte[] P1 = {1};
    private static final byte[] P2 = {2};

    @Test
    void newPack_isPendingUntilRelayed_thenNotRelayedAgain() {
        ProxyRelayState s = new ProxyRelayState();
        assertTrue(s.publish("u|a", "u", P1));
        ProxyRelayState.Pending p = s.pending();
        assertArrayEquals(P1, p.payload());
        assertTrue(s.markRelayed(p, "Alice", Instant.EPOCH));
        assertNull(s.pending());
        assertEquals("Alice", s.lastRelayedVia());
        assertEquals(Instant.EPOCH, s.lastRelayedAt());

        assertFalse(s.publish("u|a", "u", P1), "the same pack is not relayed twice");
        assertNull(s.pending());
    }

    @Test
    void newerPack_replacesPending_andAStaleCarrierDoesNotMarkIt() {
        ProxyRelayState s = new ProxyRelayState();
        s.publish("u|a", "u", P1);
        ProxyRelayState.Pending old = s.pending();
        s.publish("u|b", "u", P2);
        assertFalse(s.markRelayed(old, "Bob", Instant.EPOCH), "superseded relay");
        assertArrayEquals(P2, s.pending().payload(), "the newer pack is still pending");
    }

    @Test
    void republishingTheRelayedPack_dropsAPendingOlderOne() {
        ProxyRelayState s = new ProxyRelayState();
        s.publish("u|a", "u", P1);
        s.markRelayed(s.pending(), "A", Instant.EPOCH);
        s.publish("u|b", "u", P2);
        // Back to the pack the proxy already has (e.g. a rollback): nothing to relay.
        assertFalse(s.publish("u|a", "u", P1));
        assertNull(s.pending());
    }

    @Test
    void reset_forcesTheNextPublishToBeRelayed() {
        ProxyRelayState s = new ProxyRelayState();
        s.publish("u|a", "u", P1);
        s.markRelayed(s.pending(), "A", Instant.EPOCH);
        s.reset();
        assertTrue(s.publish("u|a", "u", P1), "after /pm reload (secret or proxy-notify changed)");
    }

    @Test
    void republishingTheWaitingPack_refreshesThePayloadWithoutANewAttempt() {
        ProxyRelayState s = new ProxyRelayState();
        assertTrue(s.publish("u|a", "u", P1));
        assertFalse(s.publish("u|a", "u", P2), "still waiting for a carrier");
        assertArrayEquals(P2, s.pending().payload());
    }
}
