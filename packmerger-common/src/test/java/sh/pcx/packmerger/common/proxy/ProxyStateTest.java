package sh.pcx.packmerger.common.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.pcx.packmerger.common.PackInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProxyStateTest {

    @TempDir
    Path dir;

    @Test
    void saveLoad_roundTrip_includingServerNamesWithDots() throws IOException {
        Path file = dir.resolve("state.properties");
        ProxyState s = new ProxyState();
        s.configFingerprint = "https://c/x.zip|";
        s.network = new ProxyState.Entry(new PackInfo("https://a/1.zip", "aa"), 42L, "lobby");
        s.servers.put("lobby", s.network);
        s.servers.put("eu.survival", new ProxyState.Entry(new PackInfo("https://b/1.zip", null), 7L, "eu.survival"));
        s.save(file);
        assertFalse(Files.exists(dir.resolve("state.properties.tmp")));

        ProxyState loaded = ProxyState.load(file);
        assertEquals(s.configFingerprint, loaded.configFingerprint);
        assertEquals(s.network, loaded.network());
        assertEquals(s.servers, loaded.servers());
        assertNull(loaded.servers().get("eu.survival").pack().sha1Hex());
    }

    @Test
    void missingFile_isEmpty() throws IOException {
        ProxyState s = ProxyState.load(dir.resolve("nope.properties"));
        assertNull(s.network());
        assertTrue(s.servers().isEmpty());
    }
}
