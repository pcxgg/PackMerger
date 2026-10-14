package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackMcmetaMergeStrategyTest {

    private final PackMcmetaMergeStrategy strategy = new PackMcmetaMergeStrategy();
    private static final MergeContext CTX = new MergeContext("pack.mcmeta", null);

    @Test
    void matches_rootMcmetaOnly() {
        assertTrue(strategy.matches("pack.mcmeta"));
        assertTrue(strategy.matches("Pack.McMeta"));
        assertFalse(strategy.matches("assets/minecraft/models/iron_sword.json"));
        assertFalse(strategy.matches("1_21_6/pack.mcmeta"));
        assertFalse(strategy.matches("foo/pack.mcmeta"));
    }

    @Test
    void overlays_entries_concat_fromBothPacks() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46, "description": "main" },
                  "overlays": {
                    "entries": [
                      { "directory": "legacy", "formats": { "min_inclusive": 15, "max_inclusive": 45 } }
                    ]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46, "description": "qa" },
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_2", "formats": { "min_inclusive": 36, "max_inclusive": 45 } },
                      { "directory": "1_21_5", "formats": 55 },
                      { "directory": "1_21_6", "formats": [63, 63] }
                    ]
                  }
                }""");

        JsonArray entries = strategy.merge(high, low, CTX)
                .getAsJsonObject("overlays")
                .getAsJsonArray("entries");

        assertEquals(4, entries.size());
        assertEquals("legacy", entries.get(0).getAsJsonObject().get("directory").getAsString());
        assertEquals("1_21_2", entries.get(1).getAsJsonObject().get("directory").getAsString());
        assertEquals("1_21_5", entries.get(2).getAsJsonObject().get("directory").getAsString());
        assertEquals("1_21_6", entries.get(3).getAsJsonObject().get("directory").getAsString());
    }

    @Test
    void overlays_entries_dedup_highWinsOnDirectoryCollision() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_6", "formats": 99 }
                    ]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_6", "formats": 63 },
                      { "directory": "1_21_5", "formats": 55 }
                    ]
                  }
                }""");

        JsonArray entries = strategy.merge(high, low, CTX)
                .getAsJsonObject("overlays")
                .getAsJsonArray("entries");

        assertEquals(2, entries.size());
        JsonObject first = entries.get(0).getAsJsonObject();
        assertEquals("1_21_6", first.get("directory").getAsString());
        assertEquals(99, first.get("formats").getAsInt());
        assertEquals("1_21_5", entries.get(1).getAsJsonObject().get("directory").getAsString());
    }

    @Test
    void packBlock_highWins_onConflictingFormat() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46, "description": "main" }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 22, "description": "qa", "supported_formats": [22, 46] }
                }""");

        JsonObject merged = strategy.merge(high, low, CTX).getAsJsonObject("pack");
        assertEquals(46, merged.get("pack_format").getAsInt());
        assertEquals("main", merged.get("description").getAsString());
        assertTrue(merged.has("supported_formats"));
    }

    @Test
    void overlays_onlyOnOneSide_survives() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46 }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46 },
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_6", "formats": 63 }
                    ]
                  }
                }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertTrue(merged.has("overlays"));
        JsonArray entries = merged.getAsJsonObject("overlays").getAsJsonArray("entries");
        assertEquals(1, entries.size());
        assertEquals("1_21_6", entries.get(0).getAsJsonObject().get("directory").getAsString());
    }

    @Test
    void noOverlays_onEitherSide_noOverlaysInResult() {
        JsonObject high = JsonMerger.parseJson("""
                { "pack": { "pack_format": 46, "description": "main" } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "pack": { "pack_format": 46, "description": "other" } }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertFalse(merged.has("overlays"));
        assertEquals("main", merged.getAsJsonObject("pack").get("description").getAsString());
    }

    @Test
    void filterBlock_concat_fromBothPacks() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46 },
                  "filter": {
                    "block": [
                      { "namespace": "minecraft", "path": "blocks/dirt" }
                    ]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46 },
                  "filter": {
                    "block": [
                      { "namespace": "minecraft", "path": "blocks/stone" }
                    ]
                  }
                }""");

        JsonArray block = strategy.merge(high, low, CTX)
                .getAsJsonObject("filter")
                .getAsJsonArray("block");
        assertEquals(2, block.size());
        assertEquals("blocks/dirt", block.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("blocks/stone", block.get(1).getAsJsonObject().get("path").getAsString());
    }

    @Test
    void filterBlock_dedup_onExactMatch() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "filter": {
                    "block": [
                      { "namespace": "minecraft", "path": "blocks/dirt" }
                    ]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "filter": {
                    "block": [
                      { "namespace": "minecraft", "path": "blocks/dirt" },
                      { "namespace": "minecraft", "path": "blocks/stone" }
                    ]
                  }
                }""");

        JsonArray block = strategy.merge(high, low, CTX)
                .getAsJsonObject("filter")
                .getAsJsonArray("block");
        assertEquals(2, block.size(), "identical namespace|path must dedup");
        assertEquals("blocks/dirt", block.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("blocks/stone", block.get(1).getAsJsonObject().get("path").getAsString());
    }

    @Test
    void filterBlock_onlyOnOneSide_survives() {
        JsonObject high = JsonMerger.parseJson("""
                { "pack": { "pack_format": 46 } }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46 },
                  "filter": {
                    "block": [
                      { "namespace": "minecraft", "path": "blocks/dirt" }
                    ]
                  }
                }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertTrue(merged.has("filter"));
        JsonArray block = merged.getAsJsonObject("filter").getAsJsonArray("block");
        assertEquals(1, block.size());
        assertEquals("blocks/dirt", block.get(0).getAsJsonObject().get("path").getAsString());
    }

    @Test
    void overlaysAndFilter_bothPresent_noInterference() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46 },
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_6", "formats": 63 }
                    ]
                  },
                  "filter": {
                    "block": [
                      { "namespace": "minecraft", "path": "blocks/dirt" }
                    ]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_5", "formats": 55 }
                    ]
                  },
                  "filter": {
                    "block": [
                      { "namespace": "minecraft", "path": "blocks/stone" }
                    ]
                  }
                }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        JsonArray entries = merged.getAsJsonObject("overlays").getAsJsonArray("entries");
        JsonArray block = merged.getAsJsonObject("filter").getAsJsonArray("block");
        assertEquals(2, entries.size());
        assertEquals(2, block.size());
    }

    // --- format range repair and overlay formats completion (G9) ------------

    @Test
    void formatRange_unionAcrossLegacyAndNewSchema() {
        List<String> notes = new ArrayList<>();
        MergeContext ctx = new MergeContext("pack.mcmeta", "new-pack", "old-pack", null, notes::add);
        JsonObject high = JsonMerger.parseJson("""
                { "pack": { "description": "new", "min_format": [84, 0], "max_format": 88 } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "pack": { "description": "old", "pack_format": 46, "supported_formats": [34, 46] } }""");

        JsonObject pack = strategy.merge(high, low, ctx).getAsJsonObject("pack");
        assertEquals("[34,64]", pack.get("supported_formats").toString(), "legacy clients see the union, capped at 64");
        assertEquals(34, pack.get("min_format").getAsInt());
        assertEquals(88, pack.get("max_format").getAsInt());
        int packFormat = pack.get("pack_format").getAsInt();
        assertTrue(packFormat >= 34 && packFormat <= 64, "pack_format must sit inside supported_formats: " + packFormat);
        assertEquals("new", pack.get("description").getAsString());
        assertEquals(1, notes.size(), notes.toString());
        assertTrue(notes.get(0).contains("34..88"), notes.get(0));
    }

    @Test
    void formatRange_newSchemaOnly_keepsMinorVersionArrays() {
        JsonObject high = JsonMerger.parseJson("""
                { "pack": { "min_format": [84, 0], "max_format": [84, 0] } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "pack": { "min_format": 69, "max_format": 75 } }""");

        JsonObject pack = strategy.merge(high, low, CTX).getAsJsonObject("pack");
        assertEquals("69", pack.get("min_format").toString());
        assertEquals("[84,0]", pack.get("max_format").toString());
        assertFalse(pack.has("supported_formats"));
        assertFalse(pack.has("pack_format"));
    }

    @Test
    void formatRange_legacyRanges_widenAndClampPackFormat() {
        JsonObject high = JsonMerger.parseJson("""
                { "pack": { "pack_format": 15, "supported_formats": { "min_inclusive": 15, "max_inclusive": 22 } } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "pack": { "pack_format": 46, "supported_formats": [34, 46] } }""");

        JsonObject pack = strategy.merge(high, low, CTX).getAsJsonObject("pack");
        assertEquals("[15,46]", pack.get("supported_formats").toString());
        assertEquals(15, pack.get("pack_format").getAsInt());
        assertFalse(pack.has("min_format"));
    }

    @Test
    void formatRange_packFormatOnly_keepsHighPriorityValue() {
        JsonObject high = JsonMerger.parseJson("{ \"pack\": { \"pack_format\": 46 } }");
        JsonObject low = JsonMerger.parseJson("{ \"pack\": { \"pack_format\": 34 } }");
        JsonObject pack = strategy.merge(high, low, CTX).getAsJsonObject("pack");
        assertEquals(46, pack.get("pack_format").getAsInt());
        assertFalse(pack.has("supported_formats"));
    }

    @Test
    void overlayFormats_completedWhenCombinedListReachesLegacyClients() {
        List<String> notes = new ArrayList<>();
        MergeContext ctx = new MergeContext("pack.mcmeta", "a", "b", null, notes::add);
        JsonObject high = JsonMerger.parseJson("""
                { "pack": { "min_format": 84, "max_format": 84 },
                  "overlays": { "entries": [ { "directory": "pm_new", "min_format": 84, "max_format": [88, 0] } ] } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "pack": { "min_format": 46, "max_format": 84 },
                  "overlays": { "entries": [
                    { "directory": "pm_old", "formats": [46, 64], "min_format": 46, "max_format": 64 },
                    { "directory": "pm_mid", "min_format": 60, "max_format": 70 }
                  ] } }""");

        JsonArray entries = strategy.merge(high, low, ctx).getAsJsonObject("overlays").getAsJsonArray("entries");
        assertEquals(3, entries.size());
        JsonObject pmNew = entries.get(0).getAsJsonObject();
        assertEquals(84, pmNew.getAsJsonObject("formats").get("min_inclusive").getAsInt());
        assertEquals(88, pmNew.getAsJsonObject("formats").get("max_inclusive").getAsInt());
        assertEquals("[46,64]", entries.get(1).getAsJsonObject().get("formats").toString(), "existing formats untouched");
        JsonObject pmMid = entries.get(2).getAsJsonObject();
        assertEquals(60, pmMid.getAsJsonObject("formats").get("min_inclusive").getAsInt());
        assertEquals(64, pmMid.getAsJsonObject("formats").get("max_inclusive").getAsInt(), "capped at 64");
        assertTrue(notes.stream().anyMatch(n -> n.contains("2 overlay entries")), notes.toString());
    }

    @Test
    void overlayFormats_notRequiredWhenAllEntriesAreNewFormat() {
        JsonObject high = JsonMerger.parseJson("""
                { "pack": { "min_format": 84, "max_format": 84 },
                  "overlays": { "entries": [ { "directory": "a", "min_format": 84, "max_format": 84 } ] } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "pack": { "min_format": 84, "max_format": 84 },
                  "overlays": { "entries": [ { "directory": "b", "min_format": 70, "max_format": 84 } ] } }""");

        JsonArray entries = strategy.merge(high, low, CTX).getAsJsonObject("overlays").getAsJsonArray("entries");
        for (var e : entries) assertFalse(e.getAsJsonObject().has("formats"), e.toString());
    }
}
