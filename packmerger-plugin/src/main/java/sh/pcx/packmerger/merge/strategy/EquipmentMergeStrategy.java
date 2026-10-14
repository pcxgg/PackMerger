package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

/**
 * Merges 1.21.2+ equipment model JSON files under {@code assets/<namespace>/equipment/}.
 *
 * <p>Equipment JSON maps layer types (e.g. {@code humanoid}, {@code humanoid_leggings},
 * {@code wings}) to arrays of layer descriptors. Two packs that each add layers for
 * different equipment slots should combine; direct layer-array conflicts default to
 * high-priority winning via {@link JsonMerger#deepMergeObjects}, which is safer than
 * blindly concatenating (layer order matters for rendering).</p>
 */
public class EquipmentMergeStrategy implements MergeStrategy {

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches(OVERLAY_PREFIX + "assets/[^/]+/equipment/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        return JsonMerger.deepMergeObjects(high, low);
    }

    @Override
    public String name() {
        return "equipment";
    }
}
