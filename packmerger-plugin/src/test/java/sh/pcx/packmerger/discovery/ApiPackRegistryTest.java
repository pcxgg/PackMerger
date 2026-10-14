package sh.pcx.packmerger.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.pcx.packmerger.merge.strategy.LanguageMergeStrategy;
import sh.pcx.packmerger.merge.strategy.PackMcmetaMergeStrategy;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ApiPackRegistryTest {

    @TempDir
    Path tmp;

    private final List<ApiPackRegistry.Registration> downloads = new ArrayList<>();

    private ApiPackRegistry registry() {
        return new ApiPackRegistry(tmp.resolve("stage").toFile(),
                List.of(new PackMcmetaMergeStrategy(), new LanguageMergeStrategy()), 1, 0, ms -> { },
                (r, dir) -> {
                    downloads.add(r);
                    try {
                        writeZip(new File(dir, r.alias() + ".zip"), "pack.mcmeta", "{\"pack\":{\"pack_format\":84}}",
                                "assets/dl/x.txt", "downloaded");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return new PluginPackStager.FetchOutcome(true, true, "fetched");
                }, note -> { });
    }

    private Path packDir(String name, String marker) throws IOException {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir.resolve("assets/api"));
        Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"" + name + "\",\"pack_format\":84}}");
        Files.writeString(dir.resolve("assets/api/" + marker + ".txt"), marker);
        return dir;
    }

    private static ApiPackRegistry.Registration local(String alias, String owner, Path p) {
        return new ApiPackRegistry.Registration(alias, owner, p, null, false, false);
    }

    private File staged(String alias) {
        return tmp.resolve("stage").resolve(alias + ".zip").toFile();
    }

    @Test
    void aliasValidation() {
        assertEquals("my-pack", ApiPackRegistry.validateAlias("My-Pack"));
        assertEquals("a.b_c-1", ApiPackRegistry.validateAlias(" a.b_c-1 "));
        for (String bad : new String[]{"", "-x", ".x", "a/b", "a b", "../x", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> ApiPackRegistry.validateAlias(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ApiPackRegistry.validateAlias(null));
    }

    @Test
    void registeredFolderIsStagedAndUnregisteringDeletesIt() throws IOException {
        ApiPackRegistry reg = registry();
        assertTrue(reg.register(local("shop", "ShopPlugin", packDir("shop", "shop"))));
        PluginPackStager.Report r = reg.stage(owner -> true);
        assertEquals(List.of("shop"), r.mergedAliases());
        assertTrue(entries(staged("shop")).contains("assets/api/shop.txt"));

        assertTrue(reg.unregister("SHOP"));
        assertFalse(reg.unregister("shop"));
        r = reg.stage(owner -> true);
        assertEquals(List.of("shop"), r.pruned());
        assertFalse(staged("shop").exists());
    }

    @Test
    void sameOwnerReplacesAnotherOwnerIsRefused() throws IOException {
        ApiPackRegistry reg = registry();
        Path a = packDir("a", "a");
        assertTrue(reg.register(local("x", "One", a)));
        assertFalse(reg.register(local("x", "one", a)), "identical registration is not a change");
        assertTrue(reg.register(local("x", "One", packDir("b", "b"))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> reg.register(local("x", "Two", a)));
        assertTrue(e.getMessage().contains("One"));
        reg.stage(o -> true);
        assertTrue(entries(staged("x")).contains("assets/api/b.txt"));
    }

    @Test
    void packsOfInactiveOwnersAreNotMergedAndOwnerRemovalDropsAll() throws IOException {
        ApiPackRegistry reg = registry();
        reg.register(local("p1", "Gone", packDir("p1", "p1")));
        reg.register(local("p2", "Gone", packDir("p2", "p2")));
        reg.register(local("p3", "Stays", packDir("p3", "p3")));
        reg.stage(o -> true);
        assertTrue(staged("p1").exists());

        PluginPackStager.Report r = reg.stage(o -> !o.equals("Gone"));
        assertEquals(List.of("p3"), r.mergedAliases());
        assertEquals(Set.of("p1", "p2"), Set.copyOf(r.pruned()));

        assertEquals(List.of("p1", "p2"), reg.unregisterOwner("gone"));
        assertEquals(List.of("p3"), reg.list().stream().map(ApiPackRegistry.Registration::alias).toList());
    }

    @Test
    void invalidSourceIsReportedAndNotMerged() throws IOException {
        ApiPackRegistry reg = registry();
        Path notAPack = tmp.resolve("notapack");
        Files.createDirectories(notAPack);
        Files.writeString(notAPack.resolve("readme.txt"), "hi");
        reg.register(local("bad", "P", notAPack));
        reg.register(local("missing", "P", tmp.resolve("nope")));
        PluginPackStager.Report r = reg.stage(o -> true);
        assertEquals(PluginPackStager.Status.INVALID, r.outcome("bad").status());
        assertEquals(PluginPackStager.Status.NOT_FOUND, r.outcome("missing").status());
        assertEquals(List.of(), r.mergedAliases());
    }

    @Test
    void clusterFolderIsUnioned() throws IOException {
        Path cluster = tmp.resolve("cluster");
        Files.createDirectories(cluster);
        Files.move(packDir("one", "one"), cluster.resolve("one"));
        Files.move(packDir("two", "two"), cluster.resolve("two"));
        ApiPackRegistry reg = registry();
        reg.register(new ApiPackRegistry.Registration("cl", "P", cluster, null, true, false));
        reg.stage(o -> true);
        List<String> e = entries(staged("cl"));
        assertTrue(e.contains("assets/api/one.txt"));
        assertTrue(e.contains("assets/api/two.txt"));
    }

    @Test
    void remoteRegistrationIsDownloadedOnRegisterAndOnRefreshOnly() throws IOException {
        ApiPackRegistry reg = registry();
        reg.register(new ApiPackRegistry.Registration("dl", "P", null, URI.create("http://example.invalid/p.zip"),
                false, true));
        assertEquals(List.of("dl"), reg.stage(o -> true).mergedAliases());
        assertEquals(1, downloads.size());
        assertTrue(downloads.get(0).allowHttp());
        assertTrue(entries(staged("dl")).contains("assets/dl/x.txt"));

        reg.stage(o -> true);
        assertEquals(1, downloads.size(), "cached download reused");

        reg.refreshAll();
        reg.stage(o -> true);
        assertEquals(2, downloads.size(), "refreshAll downloads again");
    }

    private static void writeZip(File f, String... nameContent) throws IOException {
        f.getParentFile().mkdirs();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(f.toPath()))) {
            for (int i = 0; i < nameContent.length; i += 2) {
                zos.putNextEntry(new ZipEntry(nameContent[i]));
                zos.write(nameContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    private static List<String> entries(File zip) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            return zf.stream().map(ZipEntry::getName).toList();
        }
    }
}
