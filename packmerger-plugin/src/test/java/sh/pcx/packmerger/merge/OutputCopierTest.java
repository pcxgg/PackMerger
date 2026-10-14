package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutputCopierTest {

    @Test
    void copiesIntoRelativeAndAbsoluteFoldersAndRefusesPacks(@TempDir File root) throws Exception {
        File plugins = new File(root, "plugins");
        File packs = new File(plugins, "PackMerger/packs");
        packs.mkdirs();
        File output = new File(plugins, "PackMerger/output/merged-pack.zip");
        output.getParentFile().mkdirs();
        Files.writeString(output.toPath(), "zip bytes");
        File abs = new File(root, "www");

        List<OutputCopier.Result> r = OutputCopier.copy(output,
                List.of("SomeHost/packs", abs.getAbsolutePath(), "PackMerger/packs/sub", " "), plugins, packs);
        assertEquals(3, r.size());
        assertTrue(r.get(0).ok());
        assertEquals("zip bytes", Files.readString(new File(plugins, "SomeHost/packs/merged-pack.zip").toPath()));
        assertTrue(r.get(1).ok());
        assertEquals("zip bytes", Files.readString(new File(abs, "merged-pack.zip").toPath()));
        assertFalse(r.get(2).ok());
        assertTrue(r.get(2).detail().contains("inside packs/"));
        assertFalse(new File(packs, "sub").exists());

        // Overwrites the previous copy, and leaves no temp files behind.
        Files.writeString(output.toPath(), "new bytes");
        OutputCopier.copy(output, List.of("SomeHost/packs"), plugins, packs);
        assertEquals("new bytes", Files.readString(new File(plugins, "SomeHost/packs/merged-pack.zip").toPath()));
        assertArrayEquals(new String[]{"merged-pack.zip"}, new File(plugins, "SomeHost/packs").list());
    }
}
