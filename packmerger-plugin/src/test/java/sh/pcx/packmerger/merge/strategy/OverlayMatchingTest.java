package sh.pcx.packmerger.merge.strategy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B11c: every assets/ strategy also applies to the same file inside an overlay directory. */
class OverlayMatchingTest {

    private static final Map<MergeStrategy, String> SAMPLES = Map.of(
            new SoundsMergeStrategy(), "assets/minecraft/sounds.json",
            new ModelMergeStrategy(), "assets/minecraft/models/item/stick.json",
            new BlockstateMergeStrategy(), "assets/minecraft/blockstates/stone.json",
            new AtlasMergeStrategy(), "assets/minecraft/atlases/blocks.json",
            new ItemDefinitionMergeStrategy(), "assets/minecraft/items/paper.json",
            new EquipmentMergeStrategy(), "assets/minecraft/equipment/iron.json",
            new FontMergeStrategy(), "assets/minecraft/font/default.json",
            new LanguageMergeStrategy(), "assets/minecraft/lang/en_us.json");

    @Test
    void everyAssetStrategy_matchesInsideAnOverlay() {
        SAMPLES.forEach((strategy, path) -> {
            assertTrue(strategy.matches(path), strategy.name() + " should match " + path);
            assertTrue(strategy.matches("pm_overlay/" + path), strategy.name() + " should match inside an overlay");
            assertTrue(strategy.matches("1_21_4/" + path), strategy.name() + " should match a numeric overlay name");
            assertFalse(strategy.matches("a/b/" + path), strategy.name() + " must not match two levels deep");
        });
    }

    @Test
    void packMcmeta_isRootOnly() {
        PackMcmetaMergeStrategy mcmeta = new PackMcmetaMergeStrategy();
        for (String p : List.of("pm_overlay/pack.mcmeta", "assets/pack.mcmeta")) {
            assertFalse(mcmeta.matches(p), p);
        }
    }

    @Test
    void modelPolicy_parsesConfigValues() {
        assertEquals(ModelPolicy.WHOLE_FILE, ModelPolicy.parse("whole-file"));
        assertEquals(ModelPolicy.WHOLE_FILE, ModelPolicy.parse(" Whole_File "));
        assertEquals(ModelPolicy.DEEP, ModelPolicy.parse("deep"));
        assertNull(ModelPolicy.parse("merge"));
        assertNull(ModelPolicy.parse(null));
        assertEquals("whole-file", ModelPolicy.WHOLE_FILE.configValue());
    }
}
