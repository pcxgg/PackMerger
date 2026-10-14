package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

/**
 * Merges language JSON files under {@code assets/<namespace>/lang/}.
 *
 * <p>Language files are flat maps of translation keys to strings
 * (e.g. {@code {"menu.singleplayer": "Singleplayer"}}). When multiple packs
 * add translations to the same file — a common occurrence when stacking
 * plugins that localize messages into {@code en_us.json} — a naive overwrite
 * silently drops the lower-priority pack's keys. This strategy key-unions
 * the two objects so translations from both packs survive, with the
 * higher-priority value winning on direct key collisions.</p>
 *
 * <p>Legacy pre-1.13 {@code .lang} text files are out of scope: modern
 * Minecraft (1.13+) uses JSON exclusively for translations.</p>
 */
public class LanguageMergeStrategy implements MergeStrategy {

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches(OVERLAY_PREFIX + "assets/[^/]+/lang/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        return JsonMerger.deepMergeObjects(high, low);
    }

    @Override
    public String name() {
        return "language";
    }
}
