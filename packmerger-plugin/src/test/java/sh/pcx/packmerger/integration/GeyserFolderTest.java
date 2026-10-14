package sh.pcx.packmerger.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeyserFolderTest {

    @Test
    void configuredFolderWins(@TempDir File root) {
        File plugins = new File(root, "plugins");
        new File(plugins, "Geyser-Spigot").mkdirs();
        assertEquals(new File(root, "custom/geyser"), GeyserFolder.resolve("custom/geyser", root, plugins, List.of()));
        File abs = new File(root, "abs");
        assertEquals(abs, GeyserFolder.resolve(abs.getAbsolutePath(), root, plugins, List.of()));
    }

    @Test
    void installedPluginFolderThenKnownNames(@TempDir File root) {
        File plugins = new File(root, "plugins");
        File installed = new File(plugins, "Geyser-Whatever");
        installed.mkdirs();
        assertEquals(installed, GeyserFolder.resolve("", root, plugins, List.of(installed)));

        assertNull(GeyserFolder.resolve("", root, plugins, List.of()));
        File paper = new File(plugins, "Geyser-Paper");
        paper.mkdirs();
        assertEquals(paper, GeyserFolder.resolve(null, root, plugins, List.of()));
        File spigot = new File(plugins, "Geyser-Spigot");
        spigot.mkdirs();
        assertEquals(spigot, GeyserFolder.resolve(" ", root, plugins, List.of(new File(plugins, "missing"))));
    }
}
