package sh.pcx.packmerger.integration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompetingHostFixupTest {

    private static final String IA_CONFIG = """
            # ItemsAdder config (test copy)
            resource-pack:
              # how the pack is hosted
              hosting:
                no-host:
                  enabled: false
                self-host:
                  enabled: true
                  pack-port: 8163
                lobfile:
                  enabled: false
              zip:
                protect-file-from-unzip:
                  protection_1: true
                compress-json-files: true
            other-setting: 42
            """;

    @Test
    void itemsAdderIsBackedUpAndRewritten(@TempDir Path tmp) throws Exception {
        File cfg = tmp.resolve("config.yml").toFile();
        Files.writeString(cfg.toPath(), IA_CONFIG);
        CompetingHostManager.FixupResult r =
                CompetingHostManager.applyFixup(cfg, CompetingHosts.target("itemsadder"));
        assertTrue(r.changed());
        assertEquals(IA_CONFIG, Files.readString(r.backup().toPath()), "backup is the original file");
        assertTrue(r.backup().getName().startsWith("config.yml.packmerger-backup-"));

        String written = Files.readString(cfg.toPath());
        YamlConfiguration y = YamlConfiguration.loadConfiguration(cfg);
        assertTrue(y.getBoolean("resource-pack.hosting.no-host.enabled"));
        assertFalse(y.getBoolean("resource-pack.hosting.self-host.enabled"));
        assertFalse(y.getBoolean("resource-pack.zip.protect-file-from-unzip.protection_1"));
        assertFalse(y.getBoolean("resource-pack.zip.compress-json-files"));
        assertEquals(8163, y.getInt("resource-pack.hosting.self-host.pack-port"), "other keys kept");
        assertEquals(42, y.getInt("other-setting"));
        assertTrue(written.contains("# how the pack is hosted"), "comments kept");
        assertEquals(0, CompetingHosts.detect(CompetingHosts.target("itemsadder"), y::get).size());

        // A second run has nothing to do and writes no backup.
        CompetingHostManager.FixupResult again =
                CompetingHostManager.applyFixup(cfg, CompetingHosts.target("itemsadder"));
        assertFalse(again.changed());
        assertNull(again.backup());
        try (var files = Files.list(tmp)) {
            assertEquals(2, files.count(), "config + one backup, no temp files left");
        }
    }

    @Test
    void nexoServerTypeIsSetToNone(@TempDir Path tmp) throws Exception {
        File cfg = tmp.resolve("settings.yml").toFile();
        Files.writeString(cfg.toPath(), "Pack:\n  server:\n    type: SELFHOST\n    selfhost:\n      port: 8082\n");
        assertTrue(CompetingHostManager.applyFixup(cfg, CompetingHosts.target("nexo")).changed());
        YamlConfiguration y = YamlConfiguration.loadConfiguration(cfg);
        assertEquals("NONE", y.getString("Pack.server.type"));
        assertEquals(8082, y.getInt("Pack.server.selfhost.port"));
    }

    @Test
    void missingConfigIsReported(@TempDir Path tmp) throws Exception {
        CompetingHostManager.FixupResult r = CompetingHostManager.applyFixup(
                tmp.resolve("settings.yml").toFile(), CompetingHosts.target("oraxen"));
        assertFalse(r.changed());
        assertTrue(r.message().contains("not found"));
    }
}
