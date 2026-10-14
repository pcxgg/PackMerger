package sh.pcx.packmerger.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PluginPackAdapterTest {

    @Test
    void resolveSource_firstExistingCandidateWins(@TempDir File plugins) throws IOException {
        // Adapter with two candidates; only the second exists.
        PluginPackAdapter a = PluginPackAdapter.local("x", "X",
                PluginPackAdapter.Source.pack("X/pack.zip", "X/pack"));
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
        PluginPackAdapter a = PluginPackAdapter.local("x", "X", PluginPackAdapter.Source.pack("X/default.zip"));

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
        PluginPackAdapter a = PluginPackAdapter.local("x", "X", PluginPackAdapter.Source.pack("X/pack.zip"));
        assertNull(a.resolveSource(plugins, null));
        assertNull(a.resolveSource(plugins, "does/not/exist.zip"));
    }

    @Test
    void builtIns_haveExpectedUniqueAliases() {
        List<PluginPackAdapter> adapters = PluginPackAdapter.builtIns();
        List<String> aliases = new ArrayList<>();
        for (PluginPackAdapter a : adapters) {
            aliases.add(a.alias());
            assertTrue(!a.candidatePaths().isEmpty() || a.isRemote(),
                    a.alias() + " must declare candidate paths or a url");
            assertNotNull(a.pluginName());
        }
        assertTrue(aliases.containsAll(List.of(
                "oraxen", "nexo", "itemsadder", "modelengine", "elitemobs", "freeminecraftmodels")));
        // Aliases must be unique (they become staged filenames).
        Set<String> unique = new HashSet<>(aliases);
        assertEquals(aliases.size(), unique.size(), "duplicate adapter alias");
    }

    @Test
    void builtIns_coverEveryKnownPackPlugin() {
        Map<String, PluginPackAdapter> byAlias = new HashMap<>();
        for (PluginPackAdapter a : PluginPackAdapter.builtIns()) byAlias.put(a.alias(), a);
        assertTrue(byAlias.keySet().containsAll(List.of(
                "nova", "betterhud", "valhallammo", "vane", "backpackplus", "betterstructures",
                "cannonrtp", "eternaltd", "infinitevehicles", "megablocksurvivors", "resurrectionchest",
                "mmoinventory", "realisticsurvival")), byAlias.keySet().toString());

        assertEquals("vane-core", byAlias.get("vane").pluginName());
        assertTrue(byAlias.get("mmoinventory").isRemote());
        assertTrue(byAlias.get("realisticsurvival").url().startsWith("https://"));
        assertTrue(firstCandidate(byAlias.get("elitemobs")).cluster(), "EliteMobs writes a folder of packs");
        assertEquals("EliteMobs/resource_pack", firstCandidate(byAlias.get("elitemobs")).path());
        assertEquals(2, byAlias.get("freeminecraftmodels").sources().size(), "FMM: generated zip + extra-pack cluster");
        assertEquals("FreeMinecraftModels/output/FreeMinecraftModels.zip",
                firstCandidate(byAlias.get("freeminecraftmodels")).path());
        assertEquals(List.of("MegaBlockSurvivors/resourcepack", "MegaBlockSurvivors/resource_pack"),
                byAlias.get("megablocksurvivors").candidatePaths());
        for (String cluster : List.of("betterstructures", "cannonrtp", "eternaltd", "resurrectionchest")) {
            assertTrue(firstCandidate(byAlias.get(cluster)).cluster(), cluster);
        }
    }

    @Test
    void builtIns_dropTheBrokenFallbacks() {
        Map<String, PluginPackAdapter> byAlias = new HashMap<>();
        for (PluginPackAdapter a : PluginPackAdapter.builtIns()) byAlias.put(a.alias(), a);
        assertEquals(List.of("Oraxen/pack/pack.zip"), byAlias.get("oraxen").candidatePaths());
        assertEquals(List.of("Nexo/pack/pack.zip"), byAlias.get("nexo").candidatePaths());
        assertEquals(List.of("ItemsAdder/output/generated.zip"), byAlias.get("itemsadder").candidatePaths());
    }

    @Test
    void resolve_returnsEverySourceThatExists(@TempDir File plugins) throws IOException {
        PluginPackAdapter fmm = PluginPackAdapter.builtIns().stream()
                .filter(a -> a.alias().equals("freeminecraftmodels")).findFirst().orElseThrow();
        assertTrue(fmm.resolve(plugins).isEmpty());

        File cluster = new File(plugins, "FreeMinecraftModels/resource_pack");
        assertTrue(cluster.mkdirs());
        assertEquals(1, fmm.resolve(plugins).size());
        assertTrue(fmm.resolve(plugins).get(0).cluster());

        File zip = new File(plugins, "FreeMinecraftModels/output/FreeMinecraftModels.zip");
        assertTrue(zip.getParentFile().mkdirs());
        Files.writeString(zip.toPath(), "z");
        List<PluginPackAdapter.ResolvedSource> both = fmm.resolve(plugins);
        assertEquals(2, both.size());
        assertEquals(zip.getAbsolutePath(), both.get(0).file().getAbsolutePath());
        assertFalse(both.get(0).cluster());
    }

    @Test
    void withOverride_replacesPathsKeepsClusterModeAndGate() {
        PluginPackAdapter em = PluginPackAdapter.local("elitemobs", "EliteMobs",
                PluginPackAdapter.Source.cluster("EliteMobs/resource_pack"));

        PluginPackAdapter moved = em.withOverride(new PluginPackAdapter.PluginPackOverride(
                true, null, List.of("EliteMobs/new_place"), null, null));
        assertEquals(List.of("EliteMobs/new_place"), moved.candidatePaths());
        assertTrue(firstCandidate(moved).cluster(), "cluster mode is inherited from the built-in");
        assertEquals("EliteMobs", moved.pluginName());

        PluginPackAdapter single = em.withOverride(new PluginPackAdapter.PluginPackOverride(
                true, "EliteMobsFork", List.of("a.zip", "b.zip"), false, null));
        assertEquals(List.of("a.zip", "b.zip"), single.candidatePaths());
        assertFalse(firstCandidate(single).cluster());
        assertEquals("EliteMobsFork", single.pluginName());

        PluginPackAdapter onlyEnabledFlag = em.withOverride(new PluginPackAdapter.PluginPackOverride(
                false, null, List.of(), null, null));
        assertEquals(em.candidatePaths(), onlyEnabledFlag.candidatePaths());

        PluginPackAdapter mmo = PluginPackAdapter.remote("mmoinventory", "MMOInventory", "https://old.example/p.zip");
        assertEquals("https://new.example/p.zip", mmo.withOverride(new PluginPackAdapter.PluginPackOverride(
                true, null, List.of(), null, "https://new.example/p.zip")).url());
        PluginPackAdapter local = mmo.withOverride(new PluginPackAdapter.PluginPackOverride(
                true, null, List.of("MMOInventory/pack.zip"), null, null));
        assertFalse(local.isRemote(), "a local path wins over the download");
    }

    @Test
    void custom_operatorDefinedAdapters() {
        assertNull(PluginPackAdapter.custom("nothing", new PluginPackAdapter.PluginPackOverride(
                true, "Some", List.of(), null, null)), "an adapter needs a path or url");

        PluginPackAdapter mine = PluginPackAdapter.custom("MyPack", new PluginPackAdapter.PluginPackOverride(
                true, "MyPlugin", List.of("MyPlugin/generated"), true, null));
        assertEquals("mypack", mine.alias());
        assertEquals("MyPlugin", mine.pluginName());
        assertTrue(firstCandidate(mine).cluster());

        PluginPackAdapter ungated = PluginPackAdapter.custom("shared", new PluginPackAdapter.PluginPackOverride(
                true, " ", List.of("/srv/shared/pack.zip"), null, null));
        assertNull(ungated.pluginName(), "no plugin: always active while the path exists");
    }

    @Test
    void resolvePath_relativeToPluginsFolderIncludingParent(@TempDir File root) throws IOException {
        File plugins = new File(root, "plugins");
        assertTrue(plugins.mkdirs());
        File serverRootZip = new File(root, "vane-resource-pack.zip");
        Files.writeString(serverRootZip.toPath(), "z");
        PluginPackAdapter vane = PluginPackAdapter.builtIns().stream()
                .filter(a -> a.alias().equals("vane")).findFirst().orElseThrow();
        assertEquals(serverRootZip.getCanonicalPath(), vane.resolve(plugins).get(0).file().getCanonicalPath());
    }

    private static PluginPackAdapter.Candidate firstCandidate(PluginPackAdapter a) {
        return a.sources().get(0).candidates().get(0);
    }
}
