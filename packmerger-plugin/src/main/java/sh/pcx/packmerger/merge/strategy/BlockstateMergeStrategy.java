package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Merges blockstate JSON files under {@code assets/<namespace>/blockstates/}
 * (and the same path inside an overlay directory).
 *
 * <p>Blockstates come in two shapes:</p>
 *
 * <ul>
 *   <li><strong>{@code variants}</strong> — an object mapping variant selectors
 *       (e.g. {@code "facing=north,half=top"}) to model descriptors. Under the default
 *       {@link ModelPolicy#WHOLE_FILE} policy the higher-priority file wins whole:
 *       mixing two packs' selector sets can leave states uncovered or combine
 *       selectors over different properties, which the client rejects. Under
 *       {@link ModelPolicy#DEEP} the variant maps are key-unioned (high wins per key).</li>
 *   <li><strong>{@code multipart}</strong> — an array of cases (each with {@code when}
 *       and {@code apply}). When <em>both</em> files are multipart, the arrays are
 *       concatenated and deduped by the {@code when} clause in either policy, so
 *       additions from both packs survive.</li>
 * </ul>
 *
 * <p>A {@code variants} file and a {@code multipart} file are never combined into a
 * file that has both keys under the default policy: the higher-priority one wins.</p>
 */
public class BlockstateMergeStrategy implements MergeStrategy {

    private static final String MULTIPART_KEY = "multipart";
    private static final String WHEN_KEY = "when";

    private final Supplier<ModelPolicy> policy;

    /** Uses the default {@link ModelPolicy#WHOLE_FILE} policy. */
    public BlockstateMergeStrategy() {
        this(ModelPolicy.WHOLE_FILE);
    }

    /** Uses a fixed policy. */
    public BlockstateMergeStrategy(ModelPolicy policy) {
        this(() -> policy);
    }

    /** Reads the policy on every merge, so {@code /pm reload} takes effect. */
    public BlockstateMergeStrategy(Supplier<ModelPolicy> policy) {
        this.policy = policy;
    }

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches(OVERLAY_PREFIX + "assets/[^/]+/blockstates/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        boolean deep = policy.get() == ModelPolicy.DEEP;
        boolean highHasMultipart = high.has(MULTIPART_KEY) && high.get(MULTIPART_KEY).isJsonArray();
        boolean lowHasMultipart = low.has(MULTIPART_KEY) && low.get(MULTIPART_KEY).isJsonArray();

        if (!deep && !(highHasMultipart && lowHasMultipart)) {
            if (!high.equals(low)) {
                ctx.note("blockstate: " + ctx.highLabel() + " replaced " + ctx.lowLabel()
                        + "'s copy whole (merge.model-policy: whole-file)");
            }
            return high.deepCopy();
        }

        if (!highHasMultipart && !lowHasMultipart) {
            return JsonMerger.deepMergeObjects(high, low);
        }

        JsonObject highWithout = copyWithout(high, MULTIPART_KEY);
        JsonObject result = deep
                ? JsonMerger.deepMergeObjects(highWithout, copyWithout(low, MULTIPART_KEY))
                : highWithout;

        JsonArray highArr = highHasMultipart ? high.getAsJsonArray(MULTIPART_KEY) : new JsonArray();
        JsonArray lowArr = lowHasMultipart ? low.getAsJsonArray(MULTIPART_KEY) : new JsonArray();
        JsonArray merged = JsonMerger.concatArraysWithDedup(highArr, lowArr, BlockstateMergeStrategy::multipartIdentity);
        result.add(MULTIPART_KEY, merged);
        return result;
    }

    @Override
    public String name() {
        return "blockstate";
    }

    /**
     * Dedup key for a multipart case: the stringified {@code when} clause.
     * Cases without a {@code when} (unconditional apply) are treated as unique.
     */
    private static String multipartIdentity(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has(WHEN_KEY)) return null;
        return obj.get(WHEN_KEY).toString();
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
