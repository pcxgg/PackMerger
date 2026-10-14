package sh.pcx.packmerger.common.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProxySettingsTest {

    @TempDir
    Path dir;

    @Test
    void missingFile_isCreatedWithCommentedDefaults() throws IOException {
        List<String> warnings = new ArrayList<>();
        ProxySettings s = ProxySettings.load(dir, warnings::add, Map.<String, String>of()::get);
        assertFalse(s.staticPack().hasUrl());
        assertFalse(s.hasSecret());
        assertEquals(ProxySettings.Mode.NETWORK, s.mode());
        assertTrue(s.resendOnUpdate());
        assertFalse(s.required());
        String text = Files.readString(dir.resolve(ProxySettings.FILE_NAME));
        for (String key : List.of("url=", "sha1=", "required=false", "prompt=", "secret=", "mode=network",
                "accept-from=", "resend-on-update=true")) {
            assertTrue(text.contains("\n" + key), key);
        }
        assertTrue(text.contains("# Shared secret"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void olderFile_keepsValues_andGetsTheNewKeysAppended() throws IOException {
        // The 1.2 Velocity config: url, sha1, required, prompt only.
        Files.writeString(dir.resolve(ProxySettings.FILE_NAME),
                "#PackMerger Velocity\nurl=https\\://x/y.zip\nsha1=abcd\nrequired=true\nprompt=Hello\n");
        ProxySettings s = ProxySettings.load(dir, w -> {}, Map.<String, String>of()::get);
        assertEquals("https://x/y.zip", s.staticPack().url());
        assertEquals("abcd", s.staticPack().sha1Hex());
        assertTrue(s.required());
        assertEquals("Hello", s.prompt());
        String text = Files.readString(dir.resolve(ProxySettings.FILE_NAME));
        assertTrue(text.startsWith("#PackMerger Velocity\nurl=https\\://x/y.zip"), "existing lines are untouched");
        assertTrue(text.contains("\nsecret=\n"));
        assertTrue(text.contains("\nmode=network\n"));
        assertFalse(text.contains("\nurl=\n"), "existing keys are not duplicated");
    }

    @Test
    void secretFromEnvironment_modeAndAcceptFrom() throws IOException {
        Files.writeString(dir.resolve(ProxySettings.FILE_NAME),
                "secret=${PM_SECRET}\nmode=per-server\naccept-from= a ,b,,\nresend-on-update=false\n");
        ProxySettings s = ProxySettings.load(dir, w -> {}, Map.of("PM_SECRET", "from-env")::get);
        assertEquals("from-env", s.secret());
        assertEquals(ProxySettings.Mode.PER_SERVER, s.mode());
        assertEquals(Set.of("a", "b"), s.acceptFrom());
        assertFalse(s.resendOnUpdate());
    }

    @Test
    void unsetSecretVariable_andUnknownMode_warn() throws IOException {
        Files.writeString(dir.resolve(ProxySettings.FILE_NAME), "secret=${NOPE}\nmode=everything\n");
        List<String> warnings = new ArrayList<>();
        ProxySettings s = ProxySettings.load(dir, warnings::add, Map.<String, String>of()::get);
        assertFalse(s.hasSecret());
        assertEquals(ProxySettings.Mode.NETWORK, s.mode());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("NOPE")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("unknown mode 'everything'")));
    }
}
