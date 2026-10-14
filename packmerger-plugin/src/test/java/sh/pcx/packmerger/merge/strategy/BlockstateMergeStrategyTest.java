package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class BlockstateMergeStrategyTest {

    private final BlockstateMergeStrategy strategy = new BlockstateMergeStrategy();
    private static final MergeContext CTX = new MergeContext("assets/minecraft/blockstates/stone.json", null);

    @Test
    void matches_blockstatePath() {
        assertTrue(strategy.matches("assets/minecraft/blockstates/stone.json"));
        assertFalse(strategy.matches("assets/minecraft/models/block/stone.json"));
    }

    @Test
    void variantsFromBothPacks_mergeUnderDeepPolicy() {
        BlockstateMergeStrategy strategy = new BlockstateMergeStrategy(ModelPolicy.DEEP);
        JsonObject high = JsonMerger.parseJson("""
                {
                  "variants": {
                    "facing=north": { "model": "high:north" }
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "variants": {
                    "facing=south": { "model": "low:south" }
                  }
                }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        JsonObject variants = merged.getAsJsonObject("variants");
        assertTrue(variants.has("facing=north"));
        assertTrue(variants.has("facing=south"));
    }

    @Test
    void multipartCases_concatAndDedup() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "multipart": [
                    { "when": { "north": "true" }, "apply": { "model": "high:north" } }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "multipart": [
                    { "when": { "north": "true" }, "apply": { "model": "low:north" } },
                    { "when": { "south": "true" }, "apply": { "model": "low:south" } }
                  ]
                }""");

        JsonArray cases = strategy.merge(high, low, CTX).getAsJsonArray("multipart");
        assertEquals(2, cases.size());
        // High wins on duplicate "when"
        assertEquals("high:north", cases.get(0).getAsJsonObject()
                .getAsJsonObject("apply").get("model").getAsString());
        assertEquals("low:south", cases.get(1).getAsJsonObject()
                .getAsJsonObject("apply").get("model").getAsString());
    }

    @Test
    void variants_wholeFilePolicy_highWinsWhole() {
        JsonObject high = JsonMerger.parseJson("""
                { "variants": { "facing=north": { "model": "high:north" } } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "variants": { "": { "model": "low:all" } } }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertEquals(high, merged);
    }

    @Test
    void variantsVersusMultipart_wholeFilePolicy_neverBothKeys() {
        JsonObject high = JsonMerger.parseJson("""
                { "variants": { "": { "model": "high:all" } } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "multipart": [ { "apply": { "model": "low:post" } } ] }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertTrue(merged.has("variants"));
        assertFalse(merged.has("multipart"), "a blockstate with both variants and multipart is invalid");
    }

    @Test
    void multipart_wholeFilePolicy_concatsCasesButNotOtherKeys() {
        JsonObject high = JsonMerger.parseJson("""
                { "multipart": [ { "when": { "north": "true" }, "apply": { "model": "high:north" } } ] }""");
        JsonObject low = JsonMerger.parseJson("""
                { "extra": 1, "multipart": [ { "when": { "south": "true" }, "apply": { "model": "low:south" } } ] }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertEquals(2, merged.getAsJsonArray("multipart").size());
        assertFalse(merged.has("extra"));
    }

    @Test
    void matches_blockstatesInsideOverlayDirectories() {
        assertTrue(strategy.matches("ov_1_21/assets/minecraft/blockstates/stone.json"));
    }
}
