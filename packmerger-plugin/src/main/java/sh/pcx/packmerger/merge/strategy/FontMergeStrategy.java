package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.Map;

/**
 * Merges font definition JSON files under {@code assets/<namespace>/font/}.
 *
 * <p>A font file declares an array of {@code providers} — each contributing glyphs
 * via a bitmap, TTF reference, or legacy_unicode source. Multiple packs routinely
 * add custom glyphs (chat icons, custom UI characters) to the default font; this
 * strategy concatenates {@code providers} so all of them coexist. High-priority
 * providers are listed first, which matters because Minecraft's font resolver
 * consults providers in array order.</p>
 */
public class FontMergeStrategy implements MergeStrategy {

    private static final String PROVIDERS_KEY = "providers";

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches(OVERLAY_PREFIX + "assets/[^/]+/font/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        boolean highHas = high.has(PROVIDERS_KEY) && high.get(PROVIDERS_KEY).isJsonArray();
        boolean lowHas = low.has(PROVIDERS_KEY) && low.get(PROVIDERS_KEY).isJsonArray();

        if (!highHas && !lowHas) {
            return JsonMerger.deepMergeObjects(high, low);
        }

        JsonObject highWithout = copyWithout(high, PROVIDERS_KEY);
        JsonObject lowWithout = copyWithout(low, PROVIDERS_KEY);
        JsonObject result = JsonMerger.deepMergeObjects(highWithout, lowWithout);

        JsonArray highArr = highHas ? high.getAsJsonArray(PROVIDERS_KEY) : new JsonArray();
        JsonArray lowArr = lowHas ? low.getAsJsonArray(PROVIDERS_KEY) : new JsonArray();
        JsonArray merged = JsonMerger.concatArraysWithDedup(highArr, lowArr, JsonElement::toString);
        result.add(PROVIDERS_KEY, merged);
        return result;
    }

    @Override
    public String name() {
        return "font";
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
