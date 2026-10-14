package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.Map;

/**
 * Merges texture atlas JSON files under {@code assets/<namespace>/atlases/} (1.19.3+).
 *
 * <p>An atlas declares one or more {@code sources} — each describing a directory,
 * single texture, or palette that contributes to the atlas. When two packs both
 * add sources to the same atlas (e.g. {@code blocks.json}), the combined atlas
 * should include sources from both; a replace-wins policy would drop one pack's
 * textures from the stitched atlas and produce missing-texture rendering.</p>
 */
public class AtlasMergeStrategy implements MergeStrategy {

    private static final String SOURCES_KEY = "sources";

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches(OVERLAY_PREFIX + "assets/[^/]+/atlases/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        boolean highHasSources = high.has(SOURCES_KEY) && high.get(SOURCES_KEY).isJsonArray();
        boolean lowHasSources = low.has(SOURCES_KEY) && low.get(SOURCES_KEY).isJsonArray();

        if (!highHasSources && !lowHasSources) {
            return JsonMerger.deepMergeObjects(high, low);
        }

        JsonObject highWithout = copyWithout(high, SOURCES_KEY);
        JsonObject lowWithout = copyWithout(low, SOURCES_KEY);
        JsonObject result = JsonMerger.deepMergeObjects(highWithout, lowWithout);

        JsonArray highArr = highHasSources ? high.getAsJsonArray(SOURCES_KEY) : new JsonArray();
        JsonArray lowArr = lowHasSources ? low.getAsJsonArray(SOURCES_KEY) : new JsonArray();
        // Dedup by exact structural equality so identical sources aren't duplicated
        JsonArray merged = JsonMerger.concatArraysWithDedup(highArr, lowArr, JsonElement::toString);
        result.add(SOURCES_KEY, merged);
        return result;
    }

    @Override
    public String name() {
        return "atlas";
    }

    private static JsonObject copyWithout(JsonObject source, String excludedKey) {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!entry.getKey().equals(excludedKey)) {
                copy.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return copy;
    }
}
