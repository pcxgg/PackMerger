package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelMergeStrategyTest {

    private final ModelMergeStrategy strategy = new ModelMergeStrategy();
    private static final MergeContext CTX = new MergeContext("assets/minecraft/models/item/iron_sword.json", null);

    @Test
    void matches_itemModelPath() {
        assertTrue(strategy.matches("assets/minecraft/models/item/iron_sword.json"));
        assertTrue(strategy.matches("assets/qualityarmory/models/item/sword/ak47.json"));
    }

    @Test
    void doesNotMatch_nonModelPaths() {
        assertFalse(strategy.matches("assets/minecraft/textures/item/iron_sword.png"));
        assertFalse(strategy.matches("assets/minecraft/blockstates/stone.json"));
        assertFalse(strategy.matches("assets/minecraft/sounds.json"));
    }

    /**
     * The QualityArmory regression: two packs each defining their own CustomModelData
     * overrides on the same vanilla item must end up with BOTH packs' overrides in
     * the merged output, not just the high-priority one's.
     */
    @Test
    void overridesFromBothPacks_survive() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "parent": "item/handheld",
                  "textures": { "layer0": "item/iron_sword" },
                  "overrides": [
                    { "predicate": { "custom_model_data": 500 }, "model": "custom:item/enchanted_look" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "parent": "item/handheld",
                  "textures": { "layer0": "item/iron_sword" },
                  "overrides": [
                    { "predicate": { "custom_model_data": 1001 }, "model": "qualityarmory:item/sword/quality_sword" },
                    { "predicate": { "custom_model_data": 1002 }, "model": "qualityarmory:item/sword/epic_sword" }
                  ]
                }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        JsonArray overrides = merged.getAsJsonArray("overrides");

        assertEquals(3, overrides.size(), "all three overrides must survive");
        // High-priority entries come first
        assertEquals(500, overrides.get(0).getAsJsonObject()
                .getAsJsonObject("predicate").get("custom_model_data").getAsInt());
        assertEquals(1001, overrides.get(1).getAsJsonObject()
                .getAsJsonObject("predicate").get("custom_model_data").getAsInt());
        assertEquals(1002, overrides.get(2).getAsJsonObject()
                .getAsJsonObject("predicate").get("custom_model_data").getAsInt());
    }

    @Test
    void conflictingPredicate_highPriorityWins() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1001 }, "model": "high:model" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1001 }, "model": "low:model" },
                    { "predicate": { "custom_model_data": 1002 }, "model": "low:other" }
                  ]
                }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        JsonArray overrides = merged.getAsJsonArray("overrides");

        assertEquals(2, overrides.size());
        assertEquals("high:model", overrides.get(0).getAsJsonObject().get("model").getAsString());
        assertEquals("low:other", overrides.get(1).getAsJsonObject().get("model").getAsString());
    }

    @Test
    void onlyOneSideHasOverrides_stillWorks() {
        JsonObject high = JsonMerger.parseJson("""
                { "parent": "item/handheld" }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1 }, "model": "foo:bar" }
                  ]
                }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertEquals("item/handheld", merged.get("parent").getAsString());
        assertEquals(1, merged.getAsJsonArray("overrides").size());
    }

    @Test
    void predicateKeyOrder_doesNotAffectDedup() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1, "damaged": 0 }, "model": "high" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "damaged": 0, "custom_model_data": 1 }, "model": "low" }
                  ]
                }""");

        JsonArray overrides = strategy.merge(high, low, CTX).getAsJsonArray("overrides");
        assertEquals(1, overrides.size(), "predicates with reordered keys must dedup");
        assertEquals("high", overrides.get(0).getAsJsonObject().get("model").getAsString());
    }

    @Test
    void predicateCollision_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        MergeContext ctx = new MergeContext("assets/minecraft/models/item/iron_sword.json", warnings::add);

        JsonObject high = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1000001 }, "model": "mod_a:item/knife" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1000001 }, "model": "mod_b:item/pistol" },
                    { "predicate": { "custom_model_data": 1000002 }, "model": "mod_b:item/rifle" }
                  ]
                }""");

        strategy.merge(high, low, ctx);

        assertEquals(1, warnings.size(), "exactly one collision should be reported");
        String warning = warnings.get(0);
        assertTrue(warning.contains("assets/minecraft/models/item/iron_sword.json"),
                "warning should include file path; was: " + warning);
        assertTrue(warning.contains("1000001"),
                "warning should mention the colliding predicate; was: " + warning);
    }

    @Test
    void noPredicateCollision_noWarning() {
        List<String> warnings = new ArrayList<>();
        MergeContext ctx = new MergeContext("assets/minecraft/models/item/iron_sword.json", warnings::add);

        JsonObject high = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1000001 }, "model": "mod_a:item/knife" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1000002 }, "model": "mod_b:item/pistol" }
                  ]
                }""");

        strategy.merge(high, low, ctx);

        assertTrue(warnings.isEmpty(),
                "distinct predicates must not emit collision warnings; got: " + warnings);
    }

    // --- merge.model-policy -------------------------------------------------

    private static final String HYBRID_HIGH = """
            { "parent": "minecraft:item/generated", "textures": { "layer0": "high:item/sword" } }""";
    private static final String HYBRID_LOW = """
            { "parent": "minecraft:block/cube",
              "textures": { "layer0": "low:item/sword", "side": "low:block/side" },
              "elements": [ { "from": [0, 0, 0], "to": [16, 16, 16], "faces": {} } ] }""";

    @Test
    void wholeFilePolicy_isTheDefault_andNeverBuildsHybrids() {
        JsonObject merged = strategy.merge(JsonMerger.parseJson(HYBRID_HIGH), JsonMerger.parseJson(HYBRID_LOW), CTX);
        assertEquals(JsonMerger.parseJson(HYBRID_HIGH), merged, "higher-priority model wins whole");
        assertFalse(merged.has("elements"), "a generated-parent model must not inherit the low pack's elements");
    }

    @Test
    void deepPolicy_keepsThePreviousKeyLevelMerge() {
        ModelMergeStrategy deep = new ModelMergeStrategy(ModelPolicy.DEEP);
        JsonObject merged = deep.merge(JsonMerger.parseJson(HYBRID_HIGH), JsonMerger.parseJson(HYBRID_LOW), CTX);
        assertEquals("minecraft:item/generated", merged.get("parent").getAsString());
        assertTrue(merged.has("elements"));
        assertEquals("high:item/sword", merged.getAsJsonObject("textures").get("layer0").getAsString());
        assertEquals("low:block/side", merged.getAsJsonObject("textures").get("side").getAsString());
    }

    @Test
    void wholeFilePolicy_stillConcatsOverrides_butDropsOtherLowKeys() {
        JsonObject high = JsonMerger.parseJson("""
                { "parent": "item/generated", "textures": { "layer0": "item/paper" },
                  "overrides": [ { "predicate": { "custom_model_data": 2 }, "model": "a:item/two" } ] }""");
        JsonObject low = JsonMerger.parseJson("""
                { "parent": "item/handheld", "display": { "head": { "scale": [2, 2, 2] } },
                  "overrides": [ { "predicate": { "custom_model_data": 1 }, "model": "b:item/one" } ] }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertEquals("item/generated", merged.get("parent").getAsString());
        assertFalse(merged.has("display"));
        assertEquals(2, merged.getAsJsonArray("overrides").size());
    }

    @Test
    void policySupplier_isReadOnEveryMerge() {
        ModelPolicy[] current = { ModelPolicy.WHOLE_FILE };
        ModelMergeStrategy live = new ModelMergeStrategy(() -> current[0]);
        JsonObject high = JsonMerger.parseJson(HYBRID_HIGH);
        JsonObject low = JsonMerger.parseJson(HYBRID_LOW);
        assertFalse(live.merge(high, low, CTX).has("elements"));
        current[0] = ModelPolicy.DEEP;
        assertTrue(live.merge(high, low, CTX).has("elements"));
    }

    @Test
    void overrides_areSortedByCustomModelData_stably() {
        JsonObject high = JsonMerger.parseJson("""
                { "overrides": [
                    { "predicate": { "custom_model_data": 300 }, "model": "a:item/300" },
                    { "predicate": { "custom_model_data": 5, "pulling": 1 }, "model": "a:item/5_pull" },
                    { "predicate": { "custom_model_data": 5 }, "model": "a:item/5" }
                  ] }""");
        JsonObject low = JsonMerger.parseJson("""
                { "overrides": [
                    { "predicate": { "custom_model_data": 40 }, "model": "b:item/40" },
                    { "predicate": { "pulling": 1 }, "model": "minecraft:item/bow_pulling_0" }
                  ] }""");

        JsonArray overrides = strategy.merge(high, low, CTX).getAsJsonArray("overrides");
        List<String> models = new ArrayList<>();
        overrides.forEach(o -> models.add(o.getAsJsonObject().get("model").getAsString()));
        assertEquals(List.of("minecraft:item/bow_pulling_0", "a:item/5_pull", "a:item/5", "b:item/40", "a:item/300"),
                models, "ascending CMD, no-CMD first, equal CMD keeps authored order");
    }

    @Test
    void predicateCollisionWarning_namesBothPacks() {
        List<String> warnings = new ArrayList<>();
        MergeContext ctx = new MergeContext("assets/minecraft/models/item/iron_sword.json",
                "mod_a_pack", "mod_b_pack", warnings::add, null);
        JsonObject high = JsonMerger.parseJson("""
                { "overrides": [ { "predicate": { "custom_model_data": 9 }, "model": "a" } ] }""");
        JsonObject low = JsonMerger.parseJson("""
                { "overrides": [ { "predicate": { "custom_model_data": 9 }, "model": "b" } ] }""");
        strategy.merge(high, low, ctx);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("mod_a_pack") && warnings.get(0).contains("mod_b_pack"), warnings.get(0));
    }

    @Test
    void matches_modelsInsideOverlayDirectories() {
        assertTrue(strategy.matches("overlay_legacy/assets/minecraft/models/item/iron_sword.json"));
    }
}
