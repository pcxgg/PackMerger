package sh.pcx.packmerger.upload;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class SelfHostPortTest {

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    @Test
    void minusOneIsMinecraftPortPlusOne() {
        SelfHostPort p = SelfHostPort.resolve(-1, 25565, props());
        assertEquals(25566, p.port());
        assertTrue(p.derived());
        assertTrue(p.warnings().isEmpty());
    }

    @Test
    void zeroAlsoDerives() {
        assertEquals(25566, SelfHostPort.resolve(0, 25565, props()).port());
    }

    @Test
    void explicitPortIsKept() {
        SelfHostPort p = SelfHostPort.resolve(8080, 25565, props());
        assertEquals(8080, p.port());
        assertFalse(p.derived());
        assertTrue(p.warnings().isEmpty());
    }

    @Test
    void outOfRangePortFallsBackToDerivedWithWarning() {
        SelfHostPort p = SelfHostPort.resolve(70000, 25565, props());
        assertEquals(25566, p.port());
        assertEquals(1, p.warnings().size());
        assertTrue(p.warnings().getFirst().contains("70000"));
    }

    @Test
    void minecraftPortAtTopOfRangeDerivesBelow() {
        assertEquals(65534, SelfHostPort.resolve(-1, 65535, props()).port());
    }

    @Test
    void warnsOnMinecraftPortCollision() {
        SelfHostPort p = SelfHostPort.resolve(25565, 25565, props());
        assertEquals(25565, p.port());
        assertTrue(p.warnings().getFirst().contains("Minecraft server port"));
    }

    @Test
    void warnsOnRconCollisionOnlyWhenRconEnabled() {
        assertTrue(SelfHostPort.resolve(-1, 25565, props("rcon.port", "25566")).warnings().isEmpty());
        SelfHostPort p = SelfHostPort.resolve(-1, 25565, props("enable-rcon", "true", "rcon.port", "25566"));
        assertEquals(1, p.warnings().size());
        assertTrue(p.warnings().getFirst().contains("rcon.port"));
    }

    @Test
    void warnsOnQueryCollisionOnlyWhenQueryEnabled() {
        assertTrue(SelfHostPort.resolve(25600, 25565, props("query.port", "25600")).warnings().isEmpty());
        SelfHostPort p = SelfHostPort.resolve(25600, 25565, props("enable-query", "true", "query.port", "25600"));
        assertTrue(p.warnings().getFirst().contains("query.port"));
    }

    @Test
    void malformedPropertiesAreIgnored() {
        assertTrue(SelfHostPort.resolve(-1, 25565, props("enable-rcon", "true", "rcon.port", "abc")).warnings().isEmpty());
    }
}
