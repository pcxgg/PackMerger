package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MergeProvenanceTest {

    @Test
    void builder_recordsContributors_lowestFirstAndWinnerLast() {
        MergeProvenance.Builder builder = new MergeProvenance.Builder();
        // Reverse iteration: low-priority first, high-priority last (matches engine order)
        builder.record("assets/minecraft/textures/item/sword.png", "low.zip", null, false);
        builder.record("assets/minecraft/textures/item/sword.png", "high.zip", null, false);

        MergeProvenance prov = builder.build(List.of("high.zip", "low.zip"), Instant.now());
        MergeProvenance.FileRecord rec = prov.files().get("assets/minecraft/textures/item/sword.png");

        assertNotNull(rec);
        assertEquals("high.zip", rec.winner());
        assertEquals(List.of("low.zip", "high.zip"), rec.contributors());
        assertTrue(rec.isCollision());
        assertNull(rec.strategyName());
        assertFalse(rec.wasMerged());
    }

    @Test
    void singleContributor_isNotCollision() {
        MergeProvenance.Builder builder = new MergeProvenance.Builder();
        builder.record("pack.png", "only.zip", null, false);

        MergeProvenance prov = builder.build(List.of("only.zip"), Instant.now());
        assertFalse(prov.files().get("pack.png").isCollision());
        assertEquals(0, prov.collisionCount());
    }

    @Test
    void jsonMergedPath_recordsStrategyAndWasMerged() {
        MergeProvenance.Builder builder = new MergeProvenance.Builder();
        // First pack adds the file (not yet merged — just stored)
        builder.record("assets/minecraft/models/item/iron_sword.json", "low.zip", "model", false);
        // Second pack merges with the existing JSON — now strategy fires for real
        builder.record("assets/minecraft/models/item/iron_sword.json", "high.zip", "model", true);

        MergeProvenance prov = builder.build(List.of("high.zip", "low.zip"), Instant.now());
        MergeProvenance.FileRecord rec = prov.files().get("assets/minecraft/models/item/iron_sword.json");

        assertEquals("model", rec.strategyName());
        assertTrue(rec.wasMerged());
        assertEquals(1, prov.mergedCount());
    }

    @Test
    void recordExternal_replacesPriorContributors() {
        MergeProvenance.Builder builder = new MergeProvenance.Builder();
        builder.record("pack.mcmeta", "base.zip", "pack_mcmeta", false);
        builder.recordExternal("pack.mcmeta", "<custom:pack.mcmeta>");

        MergeProvenance prov = builder.build(List.of("base.zip"), Instant.now());
        MergeProvenance.FileRecord rec = prov.files().get("pack.mcmeta");

        assertEquals("<custom:pack.mcmeta>", rec.winner());
        assertEquals(List.of("<custom:pack.mcmeta>"), rec.contributors());
    }

    @Test
    void filesByWinner_groupsCorrectly() {
        MergeProvenance.Builder builder = new MergeProvenance.Builder();
        builder.record("a.png", "low.zip", null, false);
        builder.record("a.png", "high.zip", null, false);
        builder.record("b.png", "low.zip", null, false);
        builder.record("c.png", "high.zip", null, false);

        MergeProvenance prov = builder.build(List.of("high.zip", "low.zip"), Instant.now());
        Map<String, Long> byWinner = prov.filesByWinner();

        assertEquals(2L, byWinner.get("high.zip"));
        assertEquals(1L, byWinner.get("low.zip"));
    }

    @Test
    void filesByContributor_countsEveryTouch() {
        MergeProvenance.Builder builder = new MergeProvenance.Builder();
        builder.record("a.png", "low.zip", null, false);
        builder.record("a.png", "high.zip", null, false);
        builder.record("b.png", "low.zip", null, false);

        MergeProvenance prov = builder.build(List.of("high.zip", "low.zip"), Instant.now());
        Map<String, Long> byContributor = prov.filesByContributor();

        assertEquals(1L, byContributor.get("high.zip"));
        assertEquals(2L, byContributor.get("low.zip"), "low.zip contributed to both paths");
    }

    @Test
    void jsonRoundTrip_preservesData() {
        MergeProvenance.Builder builder = new MergeProvenance.Builder();
        builder.record("a.png", "low.zip", null, false);
        builder.record("a.png", "high.zip", null, false);
        builder.record("m.json", "low.zip", "model", false);
        builder.record("m.json", "high.zip", "model", true);

        MergeProvenance original = builder.build(List.of("high.zip", "low.zip"), Instant.parse("2026-04-16T20:00:00Z"));
        MergeProvenance restored = MergeProvenance.fromJson(original.toJson());

        assertNotNull(restored);
        assertEquals(original.packOrder(), restored.packOrder());
        assertEquals(original.mergedAt(), restored.mergedAt());
        assertEquals(2, restored.files().size());
        assertEquals("high.zip", restored.files().get("a.png").winner());
        assertEquals(List.of("low.zip", "high.zip"), restored.files().get("m.json").contributors());
        assertTrue(restored.files().get("m.json").wasMerged());
    }

    @Test
    void fromJson_invalidInput_returnsNull() {
        assertNull(MergeProvenance.fromJson("not json"));
        assertNull(MergeProvenance.fromJson(""));
    }

    @Test
    void notes_areRecordedCountedAndRoundTripped() {
        MergeProvenance.Builder builder = new MergeProvenance.Builder();
        builder.record("assets/minecraft/items/paper.json", "low.zip", "item", false);
        assertEquals("low.zip", builder.winnerOf("assets/minecraft/items/paper.json"));
        assertNull(builder.winnerOf("nope"));
        builder.note("assets/minecraft/items/paper.json", "threshold 1000 is defined by both high.zip and low.zip");
        builder.record("assets/minecraft/items/paper.json", "high.zip", "item", true);
        builder.record("a.png", "high.zip", null, false);

        MergeProvenance prov = builder.build(List.of("high.zip", "low.zip"), Instant.parse("2026-10-13T10:00:00Z"));
        assertEquals(1, prov.notedCount());
        assertEquals(List.of("threshold 1000 is defined by both high.zip and low.zip"),
                prov.files().get("assets/minecraft/items/paper.json").notes());
        assertTrue(prov.files().get("a.png").notes().isEmpty());

        MergeProvenance restored = MergeProvenance.fromJson(prov.toJson());
        assertNotNull(restored);
        assertEquals(prov.files().get("assets/minecraft/items/paper.json").notes(),
                restored.files().get("assets/minecraft/items/paper.json").notes());
        assertFalse(prov.toJson().contains("\"notes\": []"), "empty notes are omitted from the sidecar");
    }
}
