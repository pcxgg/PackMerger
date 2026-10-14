package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Merges item/block model JSON files under {@code assets/<namespace>/models/}
 * (and the same path inside an overlay directory).
 *
 * <p>The critical case this strategy exists for is the {@code "overrides"} array on
 * vanilla item models. Plugins like QualityArmory add CustomModelData entries to
 * vanilla items (e.g. {@code iron_sword.json}) — if any other pack in the merge also
 * touches that file, a naive overwrite silently drops one side's overrides entirely.
 * Here we concat the arrays and dedup by predicate so that both packs'
 * CustomModelData entries survive, with higher-priority predicates winning on
 * collision. The combined list is then stably sorted by {@code custom_model_data}
 * (entries without one first), because the client picks the <em>last</em> matching
 * override and a CMD predicate matches every value at or above it.</p>
 *
 * <p>Everything outside {@code overrides} follows {@link ModelPolicy}:
 * {@link ModelPolicy#WHOLE_FILE} (default) keeps the higher-priority model as-is,
 * {@link ModelPolicy#DEEP} deep-merges the two objects key by key. Deep merging can
 * build hybrids (a {@code item/generated} parent inheriting another pack's
 * {@code elements}), which is why it is no longer the default.</p>
 *
 * <p>When two packs collide on the same predicate (e.g. both claim
 * {@code custom_model_data: 1000001} on {@code iron_sword}), the lower-priority
 * side is dropped by the dedup. A warning naming both packs is logged via the
 * merge context so operators can diagnose "my knife turned into a pistol" bug
 * reports without digging through pack diffs.</p>
 */
public class ModelMergeStrategy implements MergeStrategy {

    private static final String OVERRIDES_KEY = "overrides";
    private static final String PREDICATE_KEY = "predicate";
    private static final String CMD_KEY = "custom_model_data";

    private final Supplier<ModelPolicy> policy;

    /** Uses the default {@link ModelPolicy#WHOLE_FILE} policy. */
    public ModelMergeStrategy() {
        this(ModelPolicy.WHOLE_FILE);
    }

    /** Uses a fixed policy. */
    public ModelMergeStrategy(ModelPolicy policy) {
        this(() -> policy);
    }

    /**
     * Reads the policy on every merge, so {@code /pm reload} takes effect without
     * rebuilding the engine.
     */
    public ModelMergeStrategy(Supplier<ModelPolicy> policy) {
        this.policy = policy;
    }

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches(OVERLAY_PREFIX + "assets/[^/]+/models/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        boolean deep = policy.get() == ModelPolicy.DEEP;
        boolean highHasOverrides = high.has(OVERRIDES_KEY) && high.get(OVERRIDES_KEY).isJsonArray();
        boolean lowHasOverrides = low.has(OVERRIDES_KEY) && low.get(OVERRIDES_KEY).isJsonArray();

        if (!highHasOverrides && !lowHasOverrides) {
            if (deep) return JsonMerger.deepMergeObjects(high, low);
            if (!high.equals(low)) {
                ctx.note("model: " + ctx.highLabel() + " replaced " + ctx.lowLabel()
                        + "'s copy whole (merge.model-policy: whole-file)");
            }
            return high.deepCopy();
        }

        JsonObject highWithoutOverrides = copyWithout(high, OVERRIDES_KEY);
        JsonObject result = deep
                ? JsonMerger.deepMergeObjects(highWithoutOverrides, copyWithout(low, OVERRIDES_KEY))
                : highWithoutOverrides;

        JsonArray highArr = highHasOverrides ? high.getAsJsonArray(OVERRIDES_KEY) : new JsonArray();
        JsonArray lowArr = lowHasOverrides ? low.getAsJsonArray(OVERRIDES_KEY) : new JsonArray();

        warnOnPredicateCollisions(highArr, lowArr, ctx);

        JsonArray merged = JsonMerger.concatArraysWithDedup(highArr, lowArr, ModelMergeStrategy::overrideIdentity);
        result.add(OVERRIDES_KEY, sortByCustomModelData(merged));
        return result;
    }

    /**
     * Stable sort of an overrides list by {@code predicate.custom_model_data}
     * ascending. Overrides without a numeric CMD sort as 0, i.e. before every
     * CMD entry, so a vanilla {@code pulling}/{@code damage} override can never
     * shadow a custom model. The stable sort keeps the authored order of
     * entries that share a CMD value (e.g. a bow's pull stages).
     */
    static JsonArray sortByCustomModelData(JsonArray overrides) {
        List<JsonElement> list = new ArrayList<>();
        overrides.forEach(list::add);
        list.sort(Comparator.comparingDouble(ModelMergeStrategy::customModelData));
        JsonArray sorted = new JsonArray();
        list.forEach(sorted::add);
        return sorted;
    }

    private static double customModelData(JsonElement override) {
        if (!override.isJsonObject()) return 0;
        JsonElement predicate = override.getAsJsonObject().get(PREDICATE_KEY);
        if (predicate == null || !predicate.isJsonObject()) return 0;
        JsonElement cmd = predicate.getAsJsonObject().get(CMD_KEY);
        if (cmd == null || !cmd.isJsonPrimitive() || !cmd.getAsJsonPrimitive().isNumber()) return 0;
        return cmd.getAsDouble();
    }

    @Override
    public String name() {
        return "model";
    }

    /**
     * Emits a warning for every predicate that appears in both the high-priority and
     * low-priority overrides arrays — the lower-priority entry will be silently
     * dropped by the subsequent dedup call, and operators typically discover this
     * only when the wrong model renders in-game.
     */
    private static void warnOnPredicateCollisions(JsonArray high, JsonArray low, MergeContext ctx) {
        if (ctx == null || high.size() == 0 || low.size() == 0) return;
        Set<String> lowIdentities = new HashSet<>();
        for (JsonElement el : low) {
            String id = overrideIdentity(el);
            if (id != null) lowIdentities.add(id);
        }
        if (lowIdentities.isEmpty()) return;

        for (JsonElement el : high) {
            String id = overrideIdentity(el);
            if (id == null || !lowIdentities.contains(id)) continue;
            String summary = predicateSummary(el);
            ctx.warn("CustomModelData collision in " + ctx.path()
                    + ": predicate " + summary + " is defined by both " + ctx.highLabel()
                    + " and " + ctx.lowLabel() + "; " + ctx.highLabel() + " wins (higher priority), "
                    + ctx.lowLabel() + "'s override dropped");
        }
    }

    /**
     * Dedup key for an override entry, based on the canonical string form of its
     * {@code predicate} object. Two overrides with identical predicates are
     * considered duplicates (high priority wins). Overrides without a predicate
     * are treated as unique so they always survive.
     */
    private static String overrideIdentity(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has(PREDICATE_KEY)) return null;
        JsonElement predicate = obj.get(PREDICATE_KEY);
        if (!predicate.isJsonObject()) return predicate.toString();
        return canonicalPredicate(predicate.getAsJsonObject());
    }

    /**
     * Produces a canonical string form of a predicate object that is independent of
     * key order, so {@code {"custom_model_data": 1}} and {@code {"custom_model_data": 1}}
     * written with different whitespace/key orderings still compare equal.
     */
    private static String canonicalPredicate(JsonObject predicate) {
        StringBuilder sb = new StringBuilder("{");
        predicate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue().toString()).append(';'));
        sb.append('}');
        return sb.toString();
    }

    /**
     * Compact, human-readable form of an override's predicate for log output.
     * Falls back to the override's string form if no predicate is present.
     */
    private static String predicateSummary(JsonElement element) {
        if (!element.isJsonObject()) return element.toString();
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has(PREDICATE_KEY)) return obj.toString();
        return obj.get(PREDICATE_KEY).toString();
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
