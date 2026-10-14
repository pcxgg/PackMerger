package sh.pcx.packmerger.distribution;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PackIdentityTest {

    @Test
    void uuidIsStablePerServerAndDiffersBetweenServers() {
        UUID lobby = PackIdentity.forServer("lobby");
        assertEquals(lobby, PackIdentity.forServer("lobby"));
        assertNotEquals(lobby, PackIdentity.forServer("survival"));
        assertEquals(UUID.nameUUIDFromBytes("packmerger:lobby".getBytes(StandardCharsets.UTF_8)), lobby);
        assertEquals(PackIdentity.forServer(""), PackIdentity.forServer(null));
    }

    @Test
    void uuidDoesNotDependOnPackContent() {
        // Two different packs on the same server share one id, so a resend replaces
        // the old pack instead of stacking a second one.
        UUID id = PackIdentity.forServer("e2e");
        PublishedPack a = new PublishedPack("http://h/pack", new byte[20], id, Instant.EPOCH, 1);
        byte[] other = new byte[20];
        other[0] = 1;
        PublishedPack b = new PublishedPack("http://h/pack", other, id, Instant.EPOCH, 1);
        assertEquals(a.packId(), b.packId());
        assertFalse(a.sameContentAs(b));
    }

    @Test
    void publishedPackIsImmutable() {
        byte[] hash = new byte[20];
        PublishedPack p = new PublishedPack("http://h/pack", hash, PackIdentity.forServer("x"), Instant.EPOCH, 1);
        hash[0] = 9;
        assertEquals(0, p.sha1()[0], "constructor must copy the hash");
        p.sha1()[1] = 9;
        assertEquals(0, p.sha1()[1], "accessor must return a copy");
        assertEquals("0000000000000000000000000000000000000000", p.sha1Hex());
        assertTrue(p.sameContentAs(new PublishedPack("http://other/pack", new byte[20], p.packId(), Instant.now(), 2)));
        assertFalse(p.sameContentAs(null));
    }

    @Test
    void publishedPackRejectsBadHashes() {
        assertThrows(IllegalArgumentException.class,
                () -> new PublishedPack("u", new byte[19], UUID.randomUUID(), Instant.EPOCH, 0));
        assertThrows(NullPointerException.class,
                () -> new PublishedPack(null, new byte[20], UUID.randomUUID(), Instant.EPOCH, 0));
    }

    @Test
    void floodgateUuidHeuristic() {
        assertTrue(BedrockPlayers.looksLikeFloodgateUuid(new UUID(0L, 2535428543721L)));
        assertFalse(BedrockPlayers.looksLikeFloodgateUuid(UUID.randomUUID()));
        assertFalse(BedrockPlayers.looksLikeFloodgateUuid(new UUID(0L, 0L)));
        assertFalse(BedrockPlayers.looksLikeFloodgateUuid(null));
    }
}
