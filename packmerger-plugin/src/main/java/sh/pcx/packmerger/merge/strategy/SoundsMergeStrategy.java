package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Merges {@code assets/<namespace>/sounds.json} files.
 *
 * <p>Minecraft's sounds.json maps event names (e.g. {@code "entity.player.hurt"}) to
 * objects containing a {@code sounds} array. When two packs define sounds for the
 * same event, the desired behaviour is concat — both packs' sound variants should
 * be available for random selection at runtime rather than one replacing the other.</p>
 *
 * <p>Non-array properties ({@code replace}, {@code subtitle}) follow standard
 * high-priority-wins semantics.</p>
 */
public class SoundsMergeStrategy implements MergeStrategy {

    private static final String SOUNDS_KEY = "sounds";

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches(OVERLAY_PREFIX + "assets/[^/]+/sounds\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        JsonObject result = low.deepCopy();
        for (Map.Entry<String, JsonElement> entry : high.entrySet()) {
            String soundEvent = entry.getKey();
            JsonElement highValue = entry.getValue();

            if (result.has(soundEvent) && highValue.isJsonObject() && result.get(soundEvent).isJsonObject()) {
                JsonObject highObj = highValue.getAsJsonObject();
                JsonObject lowObj = result.getAsJsonObject(soundEvent);
                JsonObject merged = lowObj.deepCopy();

                // Concat the sounds arrays (high-priority variants first)
                if (highObj.has(SOUNDS_KEY) && lowObj.has(SOUNDS_KEY)) {
                    JsonArray concat = new JsonArray();
                    for (JsonElement e : highObj.getAsJsonArray(SOUNDS_KEY)) concat.add(e.deepCopy());
                    for (JsonElement e : lowObj.getAsJsonArray(SOUNDS_KEY)) concat.add(e.deepCopy());
                    merged.add(SOUNDS_KEY, concat);
                } else if (highObj.has(SOUNDS_KEY)) {
                    merged.add(SOUNDS_KEY, highObj.getAsJsonArray(SOUNDS_KEY).deepCopy());
                }

                // Other metadata (replace, subtitle) — high-priority wins
                for (Map.Entry<String, JsonElement> prop : highObj.entrySet()) {
                    if (!prop.getKey().equals(SOUNDS_KEY)) {
                        merged.add(prop.getKey(), prop.getValue().deepCopy());
                    }
                }

                result.add(soundEvent, merged);
            } else {
                result.add(soundEvent, highValue.deepCopy());
            }
        }
        return result;
    }

    @Override
    public String name() {
        return "sounds";
    }
}
