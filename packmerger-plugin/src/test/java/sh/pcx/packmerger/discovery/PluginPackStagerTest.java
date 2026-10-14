package sh.pcx.packmerger.discovery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.pcx.packmerger.merge.strategy.LanguageMergeStrategy;
import sh.pcx.packmerger.merge.strategy.MergeStrategy;
import sh.pcx.packmerger.merge.strategy.PackMcmetaMergeStrategy;
import sh.pcx.packmerger.discovery.PluginPackStager.Entry;
import sh.pcx.packmerger.discovery.PluginPackStager.Report;
import sh.pcx.packmerger.discovery.PluginPackStager.Status;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginPackStagerTest {

    private static final List<MergeStrategy> STRATEGIES =
            List.of(new PackMcmetaMergeStrategy(), new LanguageMergeStrategy());

    // ---------------------------------------------------------------- helpers

    private static void write(File f, String content) throws IOException {
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    /** Creates a pack folder with a pack.mcmeta and the given files. */
    private static File packDir(File dir, Map<String, String> files) throws IOException {
        write(new File(dir, "pack.mcmeta"), "{\"pack\":{\"description\":\"" + dir.getName() + "\",\"pack_format\":84}}");
        for (Map.Entry<String, String> e : files.entrySet()) write(new File(dir, e.getKey()), e.getValue());
        return dir;
    }

    private static File zip(File zip, Map<String, String> entries) throws IOException {
        zip.getParentFile().mkdirs();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    private static Map<String, String> read(File zip) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipFile zf = new ZipFile(zip)) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                out.put(e.getName(), new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static PluginPackStager stager(File plugins, File stage) {
        return new PluginPackStager(plugins, stage, STRATEGIES, 1, 0, ms -> { }, null, s -> { });
    }

    private static Entry active(PluginPackAdapter a) {
        return new Entry(a, true, true);
    }

    // ---------------------------------------------------------------- staging

    @Test
    void singleZipIsCopiedVerbatim(@TempDir File plugins, @TempDir File stage) throws IOException {
        File src = zip(new File(plugins, "Oraxen/pack/pack.zip"), Map.of("pack.mcmeta", "{}", "assets/x/a.txt", "a"));
        PluginPackAdapter a = PluginPackAdapter.local("oraxen", "Oraxen", PluginPackAdapter.Source.pack("Oraxen/pack/pack.zip"));

        Report r = stager(plugins, stage).run(List.of(active(a)), false);

        assertEquals(Status.STAGED, r.outcome("oraxen").status());
        assertArrayEquals(Files.readAllBytes(src.toPath()), Files.readAllBytes(new File(stage, "oraxen.zip").toPath()));
    }

    @Test
    void packFolderIsZippedWithForwardSlashEntries(@TempDir File plugins, @TempDir File stage) throws IOException {
        packDir(new File(plugins, "ValhallaMMO/resourcepack"),
                Map.of("assets/minecraft/models/item/stick.json", "{}"));
        PluginPackAdapter a = PluginPackAdapter.local("valhallammo", "ValhallaMMO",
                PluginPackAdapter.Source.pack("ValhallaMMO/resourcepack"));

        stager(plugins, stage).run(List.of(active(a)), false);

        Map<String, String> entries = read(new File(stage, "valhallammo.zip"));
        assertTrue(entries.containsKey("pack.mcmeta"));
        assertTrue(entries.containsKey("assets/minecraft/models/item/stick.json"), entries.keySet().toString());
    }

    @Test
    void stagedFolderIsByteIdenticalWhenOnlyMtimesChange(@TempDir File plugins, @TempDir File tmp) throws Exception {
        File src = packDir(new File(plugins, "ValhallaMMO/resourcepack"),
                Map.of("assets/minecraft/lang/en_us.json", "{\"a\":\"b\"}"));
        PluginPackAdapter a = PluginPackAdapter.local("valhallammo", "ValhallaMMO",
                PluginPackAdapter.Source.pack("ValhallaMMO/resourcepack"));

        File first = new File(tmp, "first");
        stager(plugins, first).run(List.of(active(a)), false);
        // Touch the sources so their mtimes change; content stays the same.
        for (File f : List.of(new File(src, "pack.mcmeta"), new File(src, "assets/minecraft/lang/en_us.json"))) {
            assertTrue(f.setLastModified(f.lastModified() + 120_000));
        }
        Thread.sleep(1_100);
        File second = new File(tmp, "second");
        stager(plugins, second).run(List.of(active(a)), false);

        assertArrayEquals(Files.readAllBytes(new File(first, "valhallammo.zip").toPath()),
                Files.readAllBytes(new File(second, "valhallammo.zip").toPath()));
    }

    @Test
    void clusterFolderIsUnionedWithFormatAwareJson(@TempDir File plugins, @TempDir File stage) throws IOException {
        File cluster = new File(plugins, "EliteMobs/resource_pack");
        packDir(new File(cluster, "alpha"), Map.of(
                "assets/minecraft/lang/en_us.json", "{\"a\":\"A\",\"shared\":\"from alpha\"}",
                "assets/em/shared.txt", "alpha",
                "assets/em/alpha.txt", "alpha only"));
        zip(new File(cluster, "beta.zip"), Map.of(
                "pack.mcmeta", "{\"pack\":{\"description\":\"beta\",\"pack_format\":84}}",
                "assets/minecraft/lang/en_us.json", "{\"b\":\"B\",\"shared\":\"from beta\"}",
                "assets/em/shared.txt", "beta",
                "assets/em/beta.txt", "beta only"));
        new File(cluster, "notes").mkdirs(); // not a pack: skipped
        PluginPackAdapter a = PluginPackAdapter.local("elitemobs", "EliteMobs",
                PluginPackAdapter.Source.cluster("EliteMobs/resource_pack"));
        List<String> warnings = new ArrayList<>();
        PluginPackStager s = new PluginPackStager(plugins, stage, STRATEGIES, 1, 0, ms -> { }, null, warnings::add);

        Report r = s.run(List.of(active(a)), false);

        assertEquals(Status.STAGED, r.outcome("elitemobs").status(), r.outcome("elitemobs").detail());
        Map<String, String> entries = read(new File(stage, "elitemobs.zip"));
        assertEquals("alpha only", entries.get("assets/em/alpha.txt"));
        assertEquals("beta only", entries.get("assets/em/beta.txt"));
        assertEquals("alpha", entries.get("assets/em/shared.txt"), "alphabetically first pack wins plain files");
        JsonObject lang = JsonParser.parseString(entries.get("assets/minecraft/lang/en_us.json")).getAsJsonObject();
        assertEquals("A", lang.get("a").getAsString());
        assertEquals("B", lang.get("b").getAsString());
        assertEquals("from alpha", lang.get("shared").getAsString());
        assertTrue(entries.containsKey("pack.mcmeta"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("notes")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("assets/em/shared.txt")), warnings.toString());
    }

    @Test
    void multipleSourcesAreUnionedInSourceOrder(@TempDir File plugins, @TempDir File stage) throws IOException {
        // FreeMinecraftModels: the generated zip plus a cluster of extra packs.
        zip(new File(plugins, "FreeMinecraftModels/output/FreeMinecraftModels.zip"), Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":84}}",
                "assets/fmm/models/a.json", "{\"from\":\"zip\"}",
                "assets/fmm/x.txt", "zip"));
        packDir(new File(plugins, "FreeMinecraftModels/resource_pack/extra"), Map.of(
                "assets/fmm/x.txt", "extra",
                "assets/fmm/extra.txt", "extra"));
        PluginPackAdapter fmm = builtIn("freeminecraftmodels");

        Report r = stager(plugins, stage).run(List.of(active(fmm)), false);

        assertEquals(Status.STAGED, r.outcome("freeminecraftmodels").status(), r.outcome("freeminecraftmodels").detail());
        Map<String, String> entries = read(new File(stage, "freeminecraftmodels.zip"));
        assertEquals("zip", entries.get("assets/fmm/x.txt"), "the first source wins");
        assertEquals("extra", entries.get("assets/fmm/extra.txt"));
        assertTrue(entries.containsKey("assets/fmm/models/a.json"));
    }

    @Test
    void folderOfPacksIsTreatedAsClusterAutomatically(@TempDir File plugins, @TempDir File stage) throws IOException {
        packDir(new File(plugins, "Mega/resource_pack/one"), Map.of("assets/m/one.txt", "1"));
        PluginPackAdapter a = PluginPackAdapter.local("mega", "Mega", PluginPackAdapter.Source.pack("Mega/resource_pack"));

        Report r = stager(plugins, stage).run(List.of(active(a)), false);

        assertEquals(Status.STAGED, r.outcome("mega").status());
        assertEquals("1", read(new File(stage, "mega.zip")).get("assets/m/one.txt"));
    }

    @Test
    void zipWrappedInOneFolderIsUnwrapped(@TempDir File plugins, @TempDir File stage) throws IOException {
        zip(new File(plugins, "X/pack.zip"), Map.of(
                "MyPack/pack.mcmeta", "{\"pack\":{\"pack_format\":84}}",
                "MyPack/assets/x/a.txt", "a"));
        PluginPackAdapter a = PluginPackAdapter.local("x", "X", PluginPackAdapter.Source.pack("X/pack.zip"));

        stager(plugins, stage).run(List.of(active(a)), false);

        Map<String, String> entries = read(new File(stage, "x.zip"));
        assertEquals("a", entries.get("assets/x/a.txt"));
        assertTrue(entries.containsKey("pack.mcmeta"));
    }

    // ---------------------------------------------------------------- validation

    @Test
    void sourceWithoutPackRootIsSkippedAndReported(@TempDir File plugins, @TempDir File stage) throws IOException {
        // Like the old Nexo/pack/assets fallback: the namespace folders sit at the root.
        write(new File(plugins, "Nexo/pack/assets/minecraft/models/item/stick.json"), "{}");
        PluginPackAdapter dir = PluginPackAdapter.local("nexo", "Nexo",
                PluginPackAdapter.Source.pack("Nexo/pack/assets"));
        zip(new File(plugins, "Bad/out.zip"), Map.of("contents/ns/x.yml", "a: b"));
        PluginPackAdapter badZip = PluginPackAdapter.local("bad", "Bad", PluginPackAdapter.Source.pack("Bad/out.zip"));
        write(new File(plugins, "Corrupt/pack.zip"), "this is not a zip");
        PluginPackAdapter corrupt = PluginPackAdapter.local("corrupt", "Corrupt",
                PluginPackAdapter.Source.pack("Corrupt/pack.zip"));

        Report r = stager(plugins, stage).run(List.of(active(dir), active(badZip), active(corrupt)), false);

        for (String alias : List.of("nexo", "bad", "corrupt")) {
            assertEquals(Status.INVALID, r.outcome(alias).status(), alias);
            assertTrue(r.outcome(alias).advisory(), alias + " must show in the advisory");
            assertFalse(new File(stage, alias + ".zip").exists(), alias + " must not be staged");
        }
        assertTrue(r.outcome("nexo").detail().contains("no pack.mcmeta or assets/"), r.outcome("nexo").detail());
        assertTrue(r.outcome("corrupt").detail().contains("cannot be read"), r.outcome("corrupt").detail());
    }

    @Test
    void emptyClusterIsInvalid(@TempDir File plugins, @TempDir File stage) {
        new File(plugins, "C/resource_pack").mkdirs();
        PluginPackAdapter a = PluginPackAdapter.local("c", "C", PluginPackAdapter.Source.cluster("C/resource_pack"));

        Report r = stager(plugins, stage).run(List.of(active(a)), false);

        assertEquals(Status.INVALID, r.outcome("c").status());
        assertTrue(r.outcome("c").detail().contains("contains no resource packs"));
    }

    // ---------------------------------------------------------------- stability

    @Test
    void changingSourceIsDeferredAndPreviousCopyKept(@TempDir File plugins, @TempDir File stage) throws IOException {
        File src = zip(new File(plugins, "N/pack.zip"), Map.of("pack.mcmeta", "{}", "assets/n/v.txt", "v1"));
        PluginPackAdapter a = PluginPackAdapter.local("n", "N", PluginPackAdapter.Source.pack("N/pack.zip"));
        AtomicInteger writes = new AtomicInteger();
        // Every "sleep" the plugin appends to its zip, as if it were still writing it.
        PluginPackStager.Sleeper writer = ms -> {
            if (writes.get() < 0) return;
            try {
                Files.writeString(src.toPath(), "x".repeat(writes.incrementAndGet()),
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        PluginPackStager s = new PluginPackStager(plugins, stage, STRATEGIES, 3, 1, writer, null, x -> { });

        Report first = s.run(List.of(active(a)), false);
        assertEquals(Status.DEFERRED, first.outcome("n").status());
        assertFalse(new File(stage, "n.zip").exists(), "a half-written zip must not be staged");
        assertFalse(first.outcome("n").merged());
        // Once the writes stop, the watcher notices the settled source and asks for a merge.
        writes.set(-1);
        assertFalse(s.pollForChanges(List.of(a)));
        assertTrue(s.pollForChanges(List.of(a)), "a deferred first sighting must still be retried by the watcher");

        // The plugin finishes writing: rewrite a valid zip and stop appending.
        writes.set(-1);
        zip(src, Map.of("pack.mcmeta", "{}", "assets/n/v.txt", "v2"));
        Report second = s.run(List.of(active(a)), false);
        assertEquals(Status.STAGED, second.outcome("n").status());
        assertEquals("v2", read(new File(stage, "n.zip")).get("assets/n/v.txt"));

        // The plugin starts rebuilding (and keeps writing): the previous copy is kept.
        Files.writeString(src.toPath(), "rebuilding", java.nio.file.StandardOpenOption.APPEND);
        writes.set(0);
        Report third = s.run(List.of(active(a)), false);
        assertEquals(Status.DEFERRED, third.outcome("n").status());
        assertTrue(new File(stage, "n.zip").exists(), "previous staged copy must be kept while deferred");
        assertTrue(third.mergedAliases().isEmpty());
    }

    @Test
    void unchangedSourceIsNotRecopiedAndDoesNotWait(@TempDir File plugins, @TempDir File stage) throws IOException {
        zip(new File(plugins, "N/pack.zip"), Map.of("pack.mcmeta", "{}"));
        PluginPackAdapter a = PluginPackAdapter.local("n", "N", PluginPackAdapter.Source.pack("N/pack.zip"));
        AtomicInteger sleeps = new AtomicInteger();
        PluginPackStager s = new PluginPackStager(plugins, stage, STRATEGIES, 3, 1,
                ms -> sleeps.incrementAndGet(), null, x -> { });

        assertEquals(Status.STAGED, s.run(List.of(active(a)), false).outcome("n").status());
        assertEquals(2, sleeps.get(), "a changed source is polled polls-1 more times");

        Report again = s.run(List.of(active(a)), false);
        assertEquals(Status.UNCHANGED, again.outcome("n").status());
        assertEquals(List.of("n"), again.mergedAliases());
        assertEquals(2, sleeps.get(), "an unchanged source needs no stability wait");
    }

    // ---------------------------------------------------------------- stale cleanup

    @Test
    void stagedPackIsRemovedWhenDisabledUninstalledOrGone(@TempDir File plugins, @TempDir File stage) throws IOException {
        File src = zip(new File(plugins, "N/pack.zip"), Map.of("pack.mcmeta", "{}"));
        PluginPackAdapter a = PluginPackAdapter.local("n", "N", PluginPackAdapter.Source.pack("N/pack.zip"));
        PluginPackStager s = stager(plugins, stage);
        File staged = new File(stage, "n.zip");

        s.run(List.of(active(a)), false);
        assertTrue(staged.isFile());
        Report disabled = s.run(List.of(new Entry(a, false, true)), false);
        assertEquals(Status.DISABLED, disabled.outcome("n").status());
        assertEquals(List.of("n"), disabled.pruned());
        assertFalse(staged.exists(), "disabling an adapter must delete its staged pack");

        s.run(List.of(active(a)), false);
        assertTrue(staged.isFile(), "re-enabling stages it again");
        Report uninstalled = s.run(List.of(new Entry(a, true, false)), false);
        assertEquals(Status.NOT_INSTALLED, uninstalled.outcome("n").status());
        assertFalse(uninstalled.outcome("n").advisory());
        assertFalse(staged.exists(), "removing the plugin must delete its staged pack");

        s.run(List.of(active(a)), false);
        assertTrue(src.delete());
        Report gone = s.run(List.of(active(a)), false);
        assertEquals(Status.NOT_FOUND, gone.outcome("n").status());
        assertFalse(staged.exists(), "a vanished source must delete its staged pack");
    }

    @Test
    void unknownStagedFilesArePrunedAndClearRemovesEverything(@TempDir File plugins, @TempDir File stage) throws IOException {
        write(new File(stage, "old-plugin.zip"), "stale");
        write(new File(stage, "old-plugin.meta.json"), "{}");
        File part = new File(stage, "x.zip.part");
        write(part, "partial");
        assertTrue(part.setLastModified(System.currentTimeMillis() - 120_000));
        zip(new File(plugins, "N/pack.zip"), Map.of("pack.mcmeta", "{}"));
        PluginPackAdapter a = PluginPackAdapter.local("n", "N", PluginPackAdapter.Source.pack("N/pack.zip"));
        PluginPackStager s = stager(plugins, stage);

        Report r = s.run(List.of(active(a)), false);

        assertEquals(List.of("old-plugin"), r.pruned());
        assertFalse(new File(stage, "old-plugin.meta.json").exists());
        assertFalse(part.exists());
        assertTrue(new File(stage, "n.zip").exists());

        assertEquals(List.of("n"), s.clear());
        assertFalse(new File(stage, "n.zip").exists());
    }

    @Test
    void pruneKeepsDeclaredAliasesAndFreshPartFiles(@TempDir File dir) throws IOException {
        write(new File(dir, "keep.zip"), "k");
        write(new File(dir, "keep.meta.json"), "{}");
        write(new File(dir, "gone.zip"), "g");
        write(new File(dir, "gone.meta.json"), "{}");
        write(new File(dir, "inflight.zip.part"), "p");
        write(new File(dir, "README.txt"), "not ours");

        assertEquals(List.of("gone"), PluginPackStager.prune(dir, Set.of("keep")));
        assertTrue(new File(dir, "keep.zip").exists());
        assertTrue(new File(dir, "keep.meta.json").exists());
        assertFalse(new File(dir, "gone.meta.json").exists());
        assertTrue(new File(dir, "inflight.zip.part").exists(), "a download may be writing it right now");
        assertTrue(new File(dir, "README.txt").exists());
    }

    // ---------------------------------------------------------------- watcher

    @Test
    void watcherTriggersOnlyOnSettledChangesOfSeenSources(@TempDir File plugins, @TempDir File stage) throws IOException {
        File src = zip(new File(plugins, "N/pack.zip"), Map.of("pack.mcmeta", "{}"));
        PluginPackAdapter a = PluginPackAdapter.local("n", "N", PluginPackAdapter.Source.pack("N/pack.zip"));
        PluginPackStager s = stager(plugins, stage);

        assertFalse(s.pollForChanges(List.of(a)), "never trigger before the first staging pass");
        s.run(List.of(active(a)), false);
        assertFalse(s.pollForChanges(List.of(a)), "unchanged");

        zip(src, Map.of("pack.mcmeta", "{}", "assets/n/new.txt", "new"));
        assertTrue(src.setLastModified(src.lastModified() + 5_000));
        assertFalse(s.pollForChanges(List.of(a)), "first sighting of a change waits one more poll");
        assertTrue(s.pollForChanges(List.of(a)), "a change that held for two polls triggers");

        s.run(List.of(active(a)), false);
        assertFalse(s.pollForChanges(List.of(a)), "after re-staging nothing is pending");

        assertTrue(src.delete());
        assertFalse(s.pollForChanges(List.of(a)));
        assertTrue(s.pollForChanges(List.of(a)), "a vanished source triggers a merge so it gets cleaned up");
    }

    // ---------------------------------------------------------------- URL adapters

    @Test
    void urlAdapterIsFetchedOnlyWhenNeeded(@TempDir File plugins, @TempDir File stage) {
        PluginPackAdapter a = PluginPackAdapter.remote("mmoinventory", "MMOInventory", "https://example.invalid/p.zip");
        AtomicInteger fetches = new AtomicInteger();
        PluginPackStager.UrlFetcher fetcher = (adapter, dir) -> {
            fetches.incrementAndGet();
            try {
                zip(new File(dir, adapter.alias() + ".zip"), Map.of("pack.mcmeta", "{}"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return new PluginPackStager.FetchOutcome(true, true, "fetched");
        };
        PluginPackStager s = new PluginPackStager(plugins, stage, STRATEGIES, 1, 0, ms -> { }, fetcher, x -> { });

        Report notInstalled = s.run(List.of(new Entry(a, true, false)), true);
        assertEquals(0, fetches.get(), "nothing is downloaded unless the plugin is installed");
        assertEquals(Status.NOT_INSTALLED, notInstalled.outcome("mmoinventory").status());

        assertEquals(Status.FETCHED, s.run(List.of(active(a)), false).outcome("mmoinventory").status());
        assertEquals(1, fetches.get());
        assertEquals(Status.FETCHED, s.run(List.of(active(a)), false).outcome("mmoinventory").status());
        assertEquals(1, fetches.get(), "the cached download is reused between merges");
        s.run(List.of(active(a)), true);
        assertEquals(2, fetches.get(), "refresh (startup, /pm reload) downloads again");

        s.run(List.of(new Entry(a, false, true)), false);
        assertFalse(new File(stage, "mmoinventory.zip").exists(), "disabling deletes the download");
    }

    @Test
    void failedDownloadWithoutCacheIsReported(@TempDir File plugins, @TempDir File stage) {
        PluginPackAdapter a = PluginPackAdapter.remote("realisticsurvival", "RealisticSurvival", "https://example.invalid/p.zip");
        PluginPackStager s = new PluginPackStager(plugins, stage, STRATEGIES, 1, 0, ms -> { },
                (adapter, dir) -> new PluginPackStager.FetchOutcome(false, false, "HTTP 404"), x -> { });

        Report r = s.run(List.of(active(a)), true);

        assertEquals(Status.FAILED, r.outcome("realisticsurvival").status());
        assertTrue(r.outcome("realisticsurvival").detail().contains("HTTP 404"));
        assertTrue(r.outcome("realisticsurvival").advisory());
    }

    // ---------------------------------------------------------------- ItemsAdder

    @Test
    void itemsAdderProtectionAndJsonCompressionAreDetected() {
        Map<String, Boolean> cfg = Map.of(
                "resource-pack.zip.protect-file-from-unzip.protection_2", true,
                "resource-pack.zip.compress-json-files", true);
        List<String> conflicts = ItemsAdderCheck.conflicts(k -> cfg.getOrDefault(k, false));
        assertEquals(List.of("resource-pack.zip.protect-file-from-unzip.protection_2",
                "resource-pack.zip.compress-json-files"), conflicts);
        assertTrue(ItemsAdderCheck.advisory(conflicts).contains("/iazip"));
        assertNull(ItemsAdderCheck.advisory(ItemsAdderCheck.conflicts(k -> false)));
    }

    private static PluginPackAdapter builtIn(String alias) {
        return PluginPackAdapter.builtIns().stream().filter(a -> a.alias().equals(alias)).findFirst().orElseThrow();
    }
}
