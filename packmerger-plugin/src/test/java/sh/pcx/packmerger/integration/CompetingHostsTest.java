package sh.pcx.packmerger.integration;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompetingHostsTest {

    private static List<CompetingHosts.Finding> detect(String id, Map<String, Object> cfg) {
        return CompetingHosts.detect(CompetingHosts.target(id), cfg::get);
    }

    private static List<String> keys(List<CompetingHosts.Finding> f) {
        return f.stream().map(CompetingHosts.Finding::key).toList();
    }

    @Test
    void itemsAdderHostingProtectionAndCompression() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("resource-pack.hosting.no-host.enabled", false);
        cfg.put("resource-pack.hosting.self-host.enabled", true);
        cfg.put("resource-pack.hosting.simple_self_host.enabled", true);
        cfg.put("resource-pack.hosting.external-host.enabled", false);
        cfg.put("resource-pack.hosting.lobfile.enabled", "true");
        cfg.put("resource-pack.zip.protect-file-from-unzip.protection_1", true);
        cfg.put("resource-pack.zip.protect-file-from-unzip.protection_2", false);
        cfg.put("resource-pack.zip.compress-json-files", true);
        List<CompetingHosts.Finding> f = detect("itemsadder", cfg);
        assertEquals(List.of("resource-pack.hosting.self-host.enabled",
                "resource-pack.hosting.simple_self_host.enabled",
                "resource-pack.hosting.lobfile.enabled",
                "resource-pack.zip.protect-file-from-unzip.protection_1",
                "resource-pack.zip.compress-json-files"), keys(f));
        assertEquals(CompetingHosts.Kind.HOSTING, f.get(0).kind());
        assertEquals(CompetingHosts.Kind.ZIP_PROTECTION, f.get(3).kind());
        assertEquals(CompetingHosts.Kind.JSON_COMPRESSION, f.get(4).kind());

        Map<String, Object> fixes = CompetingHosts.fixes(f);
        assertEquals(false, fixes.get("resource-pack.hosting.self-host.enabled"));
        assertEquals(false, fixes.get("resource-pack.zip.compress-json-files"));
        assertEquals(true, fixes.get("resource-pack.hosting.no-host.enabled"), "hand hosting to PackMerger");
        assertFalse(fixes.containsKey("resource-pack.hosting.external-host.enabled"), "untouched when already off");
    }

    @Test
    void itemsAdderOlderProtectionKeysAreDetected() {
        List<CompetingHosts.Finding> f = detect("itemsadder",
                Map.of("resource-pack.zip.protect-file-from-unzip.enabled", true));
        assertEquals(List.of("resource-pack.zip.protect-file-from-unzip.enabled"), keys(f));
        assertFalse(CompetingHosts.fixes(f).containsKey("resource-pack.hosting.no-host.enabled"),
                "no-host only flips when IA was hosting");
    }

    @Test
    void itemsAdderNoHostIsClean() {
        assertEquals(List.of(), detect("itemsadder", Map.of("resource-pack.hosting.no-host.enabled", true,
                "resource-pack.hosting.self-host.enabled", false)));
    }

    @Test
    void oraxenUploadAndDispatch() {
        Map<String, Object> cfg = Map.of("Pack.upload.enabled", true, "Pack.dispatch.send", true,
                "Pack.generation.protection", true);
        List<CompetingHosts.Finding> f = detect("oraxen", cfg);
        assertEquals(List.of("Pack.upload.enabled", "Pack.dispatch.send", "Pack.generation.protection"), keys(f));
        assertEquals(Map.of("Pack.upload.enabled", false, "Pack.dispatch.send", false,
                "Pack.generation.protection", false), CompetingHosts.fixes(f));
        assertEquals(List.of("Pack.dispatch.send_pack"),
                keys(detect("oraxen", Map.of("Pack.dispatch.send_pack", true, "Pack.upload.enabled", false))));
    }

    @Test
    void nexoServerType() {
        List<CompetingHosts.Finding> f = detect("nexo", Map.of("Pack.server.type", "POLYMATH"));
        assertEquals(1, f.size());
        assertEquals("POLYMATH", f.get(0).current());
        assertEquals(Map.of("Pack.server.type", "NONE"), CompetingHosts.fixes(f));
        assertEquals(List.of(), detect("nexo", Map.of("Pack.server.type", "none")));
    }

    @Test
    void missingKeysNeverWarn() {
        for (CompetingHosts.Target t : CompetingHosts.TARGETS) {
            assertEquals(List.of(), CompetingHosts.detect(t, k -> null), t.id());
        }
    }

    @Test
    void findingIdsChangeWithTheValue() {
        String a = detect("nexo", Map.of("Pack.server.type", "POLYMATH")).get(0).id();
        String b = detect("nexo", Map.of("Pack.server.type", "SELFHOST")).get(0).id();
        assertEquals("nexo:Pack.server.type=POLYMATH", a);
        assertNotEquals(a, b);
        assertTrue(detect("nexo", Map.of("Pack.server.type", "LOBFILE")).get(0).describe()
                .contains("set it to NONE"));
    }
}
