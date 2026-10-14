package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemDefinitionMergeStrategyTest {

    private static final String PATH = "assets/minecraft/items/paper.json";

    private final ItemDefinitionMergeStrategy strategy = new ItemDefinitionMergeStrategy();
    private final List<String> warnings = new ArrayList<>();
    private final MergeContext ctx = new MergeContext(PATH, "oraxen", "itemsadder", warnings::add, null);

    private static JsonObject json(String s) {
        return JsonMerger.parseJson(s);
    }

    private static List<Double> thresholds(JsonObject model) {
        List<Double> out = new ArrayList<>();
        for (JsonElement e : model.getAsJsonArray("entries")) {
            out.add(e.getAsJsonObject().get("threshold").getAsDouble());
        }
        return out;
    }

    private static String entryModel(JsonObject model, double threshold) {
        for (JsonElement e : model.getAsJsonArray("entries")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("threshold").getAsDouble() == threshold) {
                return o.getAsJsonObject("model").get("model").getAsString();
            }
        }
        return null;
    }

    private static String rangeDispatch(String property, String entries, String extra) {
        return """
                { "model": { "type": "minecraft:range_dispatch", "property": "%s"%s,
                  "entries": [ %s ],
                  "fallback": { "type": "minecraft:model", "model": "minecraft:item/paper" } } }"""
                .formatted(property, extra, entries);
    }

    private static String entry(int threshold, String model) {
        return "{ \"threshold\": %d, \"model\": { \"type\": \"minecraft:model\", \"model\": \"%s\" } }"
                .formatted(threshold, model);
    }

    @Test
    void matches_itemsPath_includingOverlays() {
        assertTrue(strategy.matches("assets/minecraft/items/iron_sword.json"));
        assertTrue(strategy.matches("overlay_1_21_4/assets/minecraft/items/iron_sword.json"));
        assertFalse(strategy.matches("assets/minecraft/models/item/iron_sword.json"));
        assertFalse(strategy.matches("a/b/assets/minecraft/items/iron_sword.json"));
    }

    @Test
    void rangeDispatch_sameProperty_unionsEntriesByThreshold_highWinsAndSorted() {
        JsonObject high = json(rangeDispatch("minecraft:custom_model_data",
                entry(1000, "oraxen:item/ruby") + "," + entry(5, "oraxen:item/topaz"), ""));
        JsonObject low = json(rangeDispatch("minecraft:custom_model_data",
                entry(1000, "itemsadder:item/knife") + "," + entry(2000, "itemsadder:item/gun") + ","
                        + entry(1, "itemsadder:item/coin"), ""));

        JsonObject model = strategy.merge(high, low, ctx).getAsJsonObject("model");

        assertEquals(List.of(1.0, 5.0, 1000.0, 2000.0), thresholds(model), "union, sorted by threshold");
        assertEquals("oraxen:item/ruby", entryModel(model, 1000), "higher priority wins the shared threshold");
        assertEquals("itemsadder:item/gun", entryModel(model, 2000), "lower-priority unique entries survive");
        assertEquals("itemsadder:item/coin", entryModel(model, 1));
        assertFalse(model.has("cases"), "never a hybrid node");
        assertEquals("minecraft:item/paper", model.getAsJsonObject("fallback").get("model").getAsString());

        assertEquals(1, warnings.size(), "one threshold collision: " + warnings);
        String w = warnings.get(0);
        assertTrue(w.contains("1000") && w.contains("oraxen") && w.contains("itemsadder") && w.contains(PATH), w);
    }

    @Test
    void rangeDispatch_identicalSharedEntry_noWarning() {
        JsonObject high = json(rangeDispatch("custom_model_data", entry(7, "x:item/same"), ""));
        JsonObject low = json(rangeDispatch("custom_model_data",
                entry(7, "x:item/same") + "," + entry(8, "y:item/other"), ""));

        JsonObject model = strategy.merge(high, low, ctx).getAsJsonObject("model");
        assertEquals(List.of(7.0, 8.0), thresholds(model));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void rangeDispatch_namespacedAndBarePropertyAreTheSame() {
        JsonObject high = json(rangeDispatch("minecraft:custom_model_data", entry(1, "a:item/a"), ""));
        JsonObject low = json(rangeDispatch("custom_model_data", entry(2, "b:item/b"), ""));
        JsonObject model = strategy.merge(high, low, ctx).getAsJsonObject("model");
        assertEquals(List.of(1.0, 2.0), thresholds(model));
    }

    @Test
    void rangeDispatch_differentProperty_highWinsWholeFileWithWarning() {
        JsonObject high = json(rangeDispatch("minecraft:damage", entry(1, "a:item/worn"), ""));
        JsonObject low = json(rangeDispatch("minecraft:custom_model_data", entry(1000, "b:item/gun"), ""));

        JsonObject merged = strategy.merge(high, low, ctx);
        assertEquals(high, merged, "higher-priority file wins whole");
        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("damage") && w.contains("custom_model_data"), w);
        assertTrue(w.contains("oraxen") && w.contains("itemsadder"), w);
    }

    @Test
    void rangeDispatch_differentParameters_highWinsWholeFile() {
        JsonObject high = json(rangeDispatch("custom_model_data", entry(1, "a:item/a"), ", \"index\": 1"));
        JsonObject low = json(rangeDispatch("custom_model_data", entry(2, "b:item/b"), ""));

        assertEquals(high, strategy.merge(high, low, ctx));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("different parameters"), warnings.get(0));
    }

    @Test
    void select_sameProperty_unionsCasesByWhen() {
        JsonObject high = json("""
                { "model": { "type": "minecraft:select", "property": "minecraft:custom_model_data",
                  "cases": [
                    { "when": "ruby", "model": { "type": "model", "model": "oraxen:item/ruby" } },
                    { "when": ["sapphire", "shared"], "model": { "type": "model", "model": "oraxen:item/blue" } }
                  ] } }""");
        JsonObject low = json("""
                { "model": { "type": "select", "property": "custom_model_data",
                  "cases": [
                    { "when": "knife", "model": { "type": "model", "model": "ia:item/knife" } },
                    { "when": ["shared", "gun"], "model": { "type": "model", "model": "ia:item/gun" } },
                    { "when": "ruby", "model": { "type": "model", "model": "ia:item/ruby" } }
                  ],
                  "fallback": { "type": "model", "model": "minecraft:item/paper" } } }""");

        JsonObject model = strategy.merge(high, low, ctx).getAsJsonObject("model");
        JsonArray cases = model.getAsJsonArray("cases");
        assertFalse(model.has("entries"), "never a hybrid node");

        assertEquals(4, cases.size(), cases.toString());
        assertEquals("ruby", cases.get(0).getAsJsonObject().get("when").getAsString());
        assertEquals("knife", cases.get(2).getAsJsonObject().get("when").getAsString());
        // The low "gun" case loses the value the high pack claimed, keeps the rest.
        assertEquals("gun", cases.get(3).getAsJsonObject().get("when").getAsString());
        assertEquals("ia:item/gun", cases.get(3).getAsJsonObject().getAsJsonObject("model").get("model").getAsString());
        // The low "ruby" case lost its only value and is gone.
        for (JsonElement c : cases) {
            assertNotEquals("ia:item/ruby", c.getAsJsonObject().getAsJsonObject("model").get("model").getAsString());
        }
        // High had no fallback: the low one is kept.
        assertEquals("minecraft:item/paper", model.getAsJsonObject("fallback").get("model").getAsString());

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("\"shared\"")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("\"ruby\"")), warnings.toString());
    }

    @Test
    void select_differentProperty_highWinsWholeFile() {
        JsonObject high = json("""
                { "model": { "type": "select", "property": "minecraft:display_context",
                  "cases": [ { "when": "gui", "model": { "type": "model", "model": "a:item/gui" } } ] } }""");
        JsonObject low = json("""
                { "model": { "type": "select", "property": "minecraft:custom_model_data",
                  "cases": [ { "when": "x", "model": { "type": "model", "model": "b:item/x" } } ] } }""");

        assertEquals(high, strategy.merge(high, low, ctx));
        assertEquals(1, warnings.size());
    }

    @Test
    void selectVersusRangeDispatch_highWinsWholeFile_noHybrid() {
        JsonObject high = json("""
                { "model": { "type": "minecraft:select", "property": "minecraft:custom_model_data",
                  "cases": [ { "when": "x", "model": { "type": "model", "model": "a:item/x" } } ] } }""");
        JsonObject low = json(rangeDispatch("minecraft:custom_model_data", entry(1, "b:item/one"), ""));

        JsonObject merged = strategy.merge(high, low, ctx);
        assertEquals(high, merged);
        JsonObject model = merged.getAsJsonObject("model");
        assertTrue(model.has("cases"));
        assertFalse(model.has("entries"), "the old deep merge produced a node with both cases and entries");
        assertFalse(model.has("fallback"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("select vs range_dispatch"), warnings.get(0));
    }

    @Test
    void plainModels_highWinsWholeFile_noTintLeak() {
        JsonObject high = json("""
                { "model": { "type": "minecraft:model", "model": "high:item/sword" } }""");
        JsonObject low = json("""
                { "model": { "type": "minecraft:model", "model": "low:item/sword",
                  "tints": [{ "type": "minecraft:constant", "value": 16711680 }] } }""");

        JsonObject model = strategy.merge(high, low, ctx).getAsJsonObject("model");
        assertEquals("high:item/sword", model.get("model").getAsString());
        assertFalse(model.has("tints"), "lower-priority tints must not leak into the winner");
        assertEquals(1, warnings.size());
    }

    @Test
    void identicalFiles_noWarning() {
        JsonObject same = json("""
                { "model": { "type": "minecraft:model", "model": "x:item/x" } }""");
        assertEquals(same, strategy.merge(same, same.deepCopy(), ctx));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void matchingDispatchFallbacks_areMergedToo() {
        JsonObject high = json("""
                { "model": { "type": "select", "property": "minecraft:display_context",
                  "cases": [ { "when": "gui", "model": { "type": "model", "model": "a:item/gui" } } ],
                  "fallback": { "type": "range_dispatch", "property": "custom_model_data",
                    "entries": [ %s ] } } }""".formatted(entry(10, "a:item/ten")));
        JsonObject low = json("""
                { "model": { "type": "select", "property": "minecraft:display_context",
                  "cases": [ { "when": "head", "model": { "type": "model", "model": "b:item/head" } } ],
                  "fallback": { "type": "range_dispatch", "property": "custom_model_data",
                    "entries": [ %s ] } } }""".formatted(entry(20, "b:item/twenty")));

        JsonObject model = strategy.merge(high, low, ctx).getAsJsonObject("model");
        assertEquals(2, model.getAsJsonArray("cases").size());
        assertEquals(List.of(10.0, 20.0), thresholds(model.getAsJsonObject("fallback")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void incompatibleFallbacks_highFallbackKeptWithWarning() {
        JsonObject high = json(rangeDispatch("custom_model_data", entry(1, "a:item/a"), "")
                .replace("minecraft:item/paper", "a:item/fallback"));
        JsonObject low = json("""
                { "model": { "type": "range_dispatch", "property": "custom_model_data",
                  "entries": [ %s ],
                  "fallback": { "type": "select", "property": "display_context", "cases": [] } } }"""
                .formatted(entry(2, "b:item/b")));

        JsonObject model = strategy.merge(high, low, ctx).getAsJsonObject("model");
        assertEquals(List.of(1.0, 2.0), thresholds(model));
        assertEquals("a:item/fallback", model.getAsJsonObject("fallback").get("model").getAsString());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("model.fallback"), warnings.get(0));
    }

    @Test
    void topLevelKeys_highWinsLowFillsGaps() {
        JsonObject high = json("""
                { "hand_animation_on_swap": false,
                  "model": { "type": "range_dispatch", "property": "custom_model_data", "entries": [] } }""");
        JsonObject low = json("""
                { "hand_animation_on_swap": true, "oversized_in_gui": true,
                  "model": { "type": "range_dispatch", "property": "custom_model_data", "entries": [] } }""");

        JsonObject merged = strategy.merge(high, low, ctx);
        assertFalse(merged.get("hand_animation_on_swap").getAsBoolean());
        assertTrue(merged.get("oversized_in_gui").getAsBoolean());
    }

    @Test
    void missingModelNode_highWinsWholeFile() {
        JsonObject high = json("{ \"oversized_in_gui\": true }");
        JsonObject low = json(rangeDispatch("custom_model_data", entry(1, "b:item/b"), ""));
        assertEquals(high, strategy.merge(high, low, ctx));
        assertEquals(1, warnings.size());
    }
}
