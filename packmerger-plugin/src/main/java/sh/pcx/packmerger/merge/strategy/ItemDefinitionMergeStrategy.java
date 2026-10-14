package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Merges 1.21.4+ item definition JSON files under {@code assets/<namespace>/items/}
 * (and the same path inside an overlay directory).
 *
 * <p>An item definition is a tree of typed model nodes. Only two node types have a
 * list that two packs can safely share:</p>
 *
 * <ul>
 *   <li><strong>{@code range_dispatch}</strong> with the same {@code property} (and the
 *       same {@code scale}/{@code index}/… parameters): the {@code entries} are unioned
 *       by {@code threshold}. On a threshold both packs use, the higher-priority entry
 *       wins and a collision warning names both packs. The result is sorted by
 *       threshold. This is the usual Oraxen + ItemsAdder + ModelEngine case: every
 *       plugin adds {@code custom_model_data} thresholds to {@code paper.json}.</li>
 *   <li><strong>{@code select}</strong> with the same {@code property} and parameters:
 *       the {@code cases} are unioned by {@code when} value. A value claimed by both
 *       packs stays with the higher-priority case (the client rejects duplicate
 *       values); a lower-priority case that loses all of its values is dropped.</li>
 * </ul>
 *
 * <p>For a merged dispatch node, the {@code fallback} is merged the same way when both
 * fallbacks are compatible dispatch nodes; otherwise the higher-priority fallback is
 * kept, or the only one present. Top-level keys besides {@code model}
 * ({@code hand_animation_on_swap}, …) follow high-priority-wins.</p>
 *
 * <p>Every other combination (different node types, different dispatch property,
 * plain {@code model}/{@code composite}/{@code special}/… nodes) is not mergeable: the
 * higher-priority file wins whole, and a warning naming both packs is emitted. A
 * merged node never carries both {@code cases} and {@code entries}.</p>
 */
public class ItemDefinitionMergeStrategy implements MergeStrategy {

    private static final String MODEL = "model";
    private static final String TYPE = "type";
    private static final String PROPERTY = "property";
    private static final String ENTRIES = "entries";
    private static final String THRESHOLD = "threshold";
    private static final String CASES = "cases";
    private static final String WHEN = "when";
    private static final String FALLBACK = "fallback";

    private static final String RANGE_DISPATCH = "range_dispatch";
    private static final String SELECT = "select";

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches(OVERLAY_PREFIX + "assets/[^/]+/items/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        if (high.equals(low)) return high.deepCopy();

        JsonObject highModel = objectOrNull(high, MODEL);
        JsonObject lowModel = objectOrNull(low, MODEL);
        if (highModel == null || lowModel == null) {
            return wholeFile(high, ctx, "one of them has no 'model' node");
        }

        String reason = incompatibility(highModel, lowModel);
        if (reason != null) {
            return wholeFile(high, ctx, reason);
        }

        JsonObject result = high.deepCopy();
        for (Map.Entry<String, JsonElement> e : low.entrySet()) {
            if (!result.has(e.getKey())) result.add(e.getKey(), e.getValue().deepCopy());
        }
        result.add(MODEL, mergeNode(highModel, lowModel, ctx, MODEL));
        return result;
    }

    @Override
    public String name() {
        return "item";
    }

    private static JsonObject wholeFile(JsonObject high, MergeContext ctx, String reason) {
        ctx.warn("Item definition collision in " + ctx.path() + ": " + ctx.highLabel() + " and "
                + ctx.lowLabel() + " both define it but the files cannot be combined (" + reason + "); "
                + ctx.highLabel() + "'s file wins whole (higher priority)");
        return high.deepCopy();
    }

    /**
     * @return {@code null} if the two nodes are dispatch nodes of the same type and
     *         parameters (so their lists can be unioned), otherwise a short reason
     */
    static String incompatibility(JsonObject high, JsonObject low) {
        String highType = normalizedType(high);
        String lowType = normalizedType(low);
        if (!highType.equals(lowType)) {
            return "model types differ: " + display(highType) + " vs " + display(lowType);
        }
        if (!highType.equals(RANGE_DISPATCH) && !highType.equals(SELECT)) {
            return "both are " + display(highType) + " nodes, which have no list to combine";
        }
        String highProp = normalizedId(high.get(PROPERTY));
        String lowProp = normalizedId(low.get(PROPERTY));
        if (!highProp.equals(lowProp)) {
            return highType + " on different properties: " + display(highProp) + " vs " + display(lowProp);
        }
        if (!dispatchParameters(high).equals(dispatchParameters(low))) {
            return highType + " on " + highProp + " with different parameters";
        }
        return null;
    }

    /** Merges two compatible dispatch nodes (see {@link #incompatibility}). */
    private static JsonObject mergeNode(JsonObject high, JsonObject low, MergeContext ctx, String where) {
        JsonObject result = high.deepCopy();
        if (normalizedType(high).equals(RANGE_DISPATCH)) {
            result.add(ENTRIES, mergeEntries(arrayOrEmpty(high, ENTRIES), arrayOrEmpty(low, ENTRIES), ctx, where));
        } else {
            result.add(CASES, mergeCases(arrayOrEmpty(high, CASES), arrayOrEmpty(low, CASES), ctx, where));
        }

        JsonObject highFallback = objectOrNull(high, FALLBACK);
        JsonObject lowFallback = objectOrNull(low, FALLBACK);
        if (highFallback == null && lowFallback != null) {
            result.add(FALLBACK, lowFallback.deepCopy());
        } else if (highFallback != null && lowFallback != null && !highFallback.equals(lowFallback)) {
            String fallbackWhere = where + "." + FALLBACK;
            if (incompatibility(highFallback, lowFallback) == null) {
                result.add(FALLBACK, mergeNode(highFallback, lowFallback, ctx, fallbackWhere));
            } else {
                ctx.warn("Item definition collision in " + ctx.path() + ": " + fallbackWhere + " differs between "
                        + ctx.highLabel() + " and " + ctx.lowLabel() + "; " + ctx.highLabel() + "'s fallback is used");
            }
        }
        return result;
    }

    /** Union of range_dispatch entries by threshold, high wins on a shared threshold, sorted ascending. */
    private static JsonArray mergeEntries(JsonArray high, JsonArray low, MergeContext ctx, String where) {
        TreeMap<Double, JsonElement> byThreshold = new TreeMap<>();
        List<JsonElement> malformed = new ArrayList<>();
        for (JsonElement e : high) {
            Double t = threshold(e);
            if (t == null) malformed.add(e.deepCopy());
            else byThreshold.putIfAbsent(t, e.deepCopy());
        }
        for (JsonElement e : low) {
            Double t = threshold(e);
            if (t == null) {
                malformed.add(e.deepCopy());
                continue;
            }
            JsonElement existing = byThreshold.get(t);
            if (existing == null) {
                byThreshold.put(t, e.deepCopy());
            } else if (!existing.equals(e)) {
                ctx.warn("Item definition collision in " + ctx.path() + ": " + where + " threshold "
                        + formatNumber(t) + " is defined by both " + ctx.highLabel() + " and " + ctx.lowLabel()
                        + "; " + ctx.highLabel() + " wins (higher priority), " + ctx.lowLabel() + "'s entry dropped");
            }
        }
        JsonArray out = new JsonArray();
        byThreshold.values().forEach(out::add);
        malformed.forEach(out::add);
        return out;
    }

    /**
     * Union of select cases by {@code when} value. High-priority cases come first and
     * keep every value they list; a lower-priority case keeps only the values no
     * higher case claimed, and is dropped if none remain.
     */
    private static JsonArray mergeCases(JsonArray high, JsonArray low, MergeContext ctx, String where) {
        JsonArray out = new JsonArray();
        Set<String> claimed = new LinkedHashSet<>();
        for (JsonElement c : high) {
            out.add(c.deepCopy());
            claimed.addAll(whenValues(c).keySet());
        }
        for (JsonElement c : low) {
            Map<String, JsonElement> values = whenValues(c);
            if (values.isEmpty()) {
                out.add(c.deepCopy());
                continue;
            }
            List<JsonElement> kept = new ArrayList<>();
            List<String> lost = new ArrayList<>();
            for (Map.Entry<String, JsonElement> v : values.entrySet()) {
                if (claimed.add(v.getKey())) kept.add(v.getValue());
                else lost.add(v.getKey());
            }
            if (!lost.isEmpty() && !isDuplicateOfHighCase(c, high)) {
                ctx.warn("Item definition collision in " + ctx.path() + ": " + where + " select case "
                        + String.join(", ", lost) + " is defined by both " + ctx.highLabel() + " and "
                        + ctx.lowLabel() + "; " + ctx.highLabel() + " wins (higher priority)");
            }
            if (kept.isEmpty()) continue;
            JsonObject copy = c.getAsJsonObject().deepCopy();
            if (kept.size() != values.size()) {
                if (kept.size() == 1) {
                    copy.add(WHEN, kept.get(0).deepCopy());
                } else {
                    JsonArray when = new JsonArray();
                    kept.forEach(k -> when.add(k.deepCopy()));
                    copy.add(WHEN, when);
                }
            }
            out.add(copy);
        }
        return out;
    }

    private static boolean isDuplicateOfHighCase(JsonElement lowCase, JsonArray high) {
        for (JsonElement h : high) if (h.equals(lowCase)) return true;
        return false;
    }

    /** {@code when} values of a select case keyed by their JSON form; empty if malformed. */
    private static Map<String, JsonElement> whenValues(JsonElement selectCase) {
        Map<String, JsonElement> out = new java.util.LinkedHashMap<>();
        if (!selectCase.isJsonObject()) return out;
        JsonElement when = selectCase.getAsJsonObject().get(WHEN);
        if (when == null) return out;
        if (when.isJsonArray()) {
            for (JsonElement v : when.getAsJsonArray()) out.putIfAbsent(v.toString(), v);
        } else {
            out.put(when.toString(), when);
        }
        return out;
    }

    private static Double threshold(JsonElement entry) {
        if (!entry.isJsonObject()) return null;
        JsonElement t = entry.getAsJsonObject().get(THRESHOLD);
        if (t == null || !t.isJsonPrimitive() || !t.getAsJsonPrimitive().isNumber()) return null;
        return t.getAsDouble();
    }

    /** Every key except type and the mergeable lists, with namespaced ids normalized. */
    private static JsonObject dispatchParameters(JsonObject node) {
        JsonObject params = new JsonObject();
        node.entrySet().stream()
                .filter(e -> !e.getKey().equals(TYPE) && !e.getKey().equals(ENTRIES)
                        && !e.getKey().equals(CASES) && !e.getKey().equals(FALLBACK))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(e -> params.add(e.getKey(), e.getKey().equals(PROPERTY)
                        ? new com.google.gson.JsonPrimitive(normalizedId(e.getValue()))
                        : e.getValue()));
        return params;
    }

    private static String normalizedType(JsonObject node) {
        return normalizedId(node.get(TYPE));
    }

    /** {@code "minecraft:select"} and {@code "select"} name the same thing. */
    private static String normalizedId(JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) return "";
        String s = el.getAsString();
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    private static String display(String id) {
        return id.isEmpty() ? "(none)" : id;
    }

    private static String formatNumber(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static JsonObject objectOrNull(JsonObject source, String key) {
        JsonElement e = source.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arrayOrEmpty(JsonObject source, String key) {
        JsonElement e = source.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
