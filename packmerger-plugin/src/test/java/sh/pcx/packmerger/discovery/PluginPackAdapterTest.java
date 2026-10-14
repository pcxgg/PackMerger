package sh.pcx.packmerger.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PluginPackAdapterTest {

    @Test
    void resolveSource_firstExistingCandidateWins(@TempDir File plugins) throws IOException {
        // Adapter with two candidates; only the second exists.
        PluginPackAdapter a = new PluginPackAdapter("x", "X",
                List.of("X/pack.zip", "X/pack"));
        File dir = new File(plugins, "X/pack");
        assertTrue(dir.mkdirs());

        File resolved = a.resolveSource(plugins, null);
        assertNotNull(resolved);
        assertEquals(dir.getAbsolutePath(), resolved.getAbsolutePath());

        // Now the higher-priority candidate exists too — it must win.
        File zip = new File(plugins, "X/pack.zip");
        Files.writeString(zip.toPath(), "x");
        assertEquals(zip.getAbsolutePath(), a.resolveSource(plugins, null).getAbsolutePath());
    }

    @Test
    void resolveSource_overrideRelativeAndAbsolute(@TempDir File plugins) throws IOException {
        PluginPackAdapter a = new PluginPackAdapter("x", "X", List.of("X/default.zip"));

        // Relative override resolves against the plugins folder.
        File custom = new File(plugins, "custom/my.zip");
        assertTrue(custom.getParentFile().mkdirs());
        Files.writeString(custom.toPath(), "z");
        assertEquals(custom.getAbsolutePath(),
                a.resolveSource(plugins, "custom/my.zip").getAbsolutePath());

        // Absolute override is used verbatim.
        assertEquals(custom.getAbsolutePath(),
                a.resolveSource(plugins, custom.getAbsolutePath()).getAbsolutePath());
    }

    @Test
    void resolveSource_nothingFound_returnsNull(@TempDir File plugins) {
        PluginPackAdapter a = new PluginPackAdapter("x", "X", List.of("X/pack.zip"));
        assertNull(a.resolveSource(plugins, null));
        assertNull(a.resolveSource(plugins, "does/not/exist.zip"));
    }

    @Test
    void builtIns_haveExpectedUniqueAliases() {
        List<PluginPackAdapter> adapters = PluginPackAdapter.builtIns();
        List<String> aliases = new ArrayList<>();
        for (PluginPackAdapter a : adapters) {
            aliases.add(a.alias());
            assertFalse(a.candidatePaths().isEmpty(), a.alias() + " must declare candidate paths");
            assertNotNull(a.pluginName());
        }
        assertTrue(aliases.containsAll(List.of(
                "oraxen", "nexo", "itemsadder", "modelengine", "elitemobs", "freeminecraftmodels")));
        // Aliases must be unique (they become staged filenames).
        Set<String> unique = new HashSet<>(aliases);
        assertEquals(aliases.size(), unique.size(), "duplicate adapter alias");
    }

    @Test
    void zipDirectory_producesEntriesWithForwardSlashes(@TempDir File tmp) throws IOException {
        File src = new File(tmp, "src");
        assertTrue(new File(src, "assets/minecraft/models/item").mkdirs());
        Files.writeString(new File(src, "pack.mcmeta").toPath(), "{}");
        Files.writeString(new File(src, "assets/minecraft/models/item/stick.json").toPath(), "{}");

        File zip = new File(tmp, "out.zip");
        PluginPackManager.zipDirectory(src, zip);

        Set<String> entries = new HashSet<>();
        try (ZipFile zf = new ZipFile(zip)) {
            var e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                entries.add(entry.getName());
            }
        }
        assertTrue(entries.contains("pack.mcmeta"));
        assertTrue(entries.contains("assets/minecraft/models/item/stick.json"),
                "entries must use forward slashes relative to the dir root: " + entries);
    }
    @Test
    void zipDirectory_isByteIdenticalForUnchangedContent(@TempDir File tmp) throws Exception {
        File src = new File(tmp, "src");
        assertTrue(new File(src, "assets/minecraft/lang").mkdirs());
        Files.writeString(new File(src, "pack.mcmeta").toPath(), "{}");
        Files.writeString(new File(src, "assets/minecraft/lang/en_us.json").toPath(), "{\"a\":\"b\"}");

        File first = new File(tmp, "first.zip");
        PluginPackManager.zipDirectory(src, first);
        // Touch the sources so their mtimes change; content stays the same.
        for (File f : List.of(new File(src, "pack.mcmeta"), new File(src, "assets/minecraft/lang/en_us.json"))) {
            assertTrue(f.setLastModified(f.lastModified() + 120_000));
        }
        Thread.sleep(1_100);
        File second = new File(tmp, "second.zip");
        PluginPackManager.zipDirectory(src, second);

        assertArrayEquals(Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath()));
    }
}
