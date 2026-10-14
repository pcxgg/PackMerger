package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonObject;

/**
 * Defines how a specific Minecraft resource-pack JSON file format should be merged
 * when two or more packs both contain the same file path.
 *
 * <p>The default behaviour for files without a matching strategy is simple overwrite
 * (higher priority wins). Strategies exist to preserve data that would otherwise be
 * silently lost — most importantly, array-valued keys like {@code overrides} in item
 * models or {@code sources} in atlases, which {@link sh.pcx.packmerger.merge.JsonMerger#deepMergeObjects}
 * alone cannot combine safely.</p>
 *
 * <p>Implementations are expected to be stateless and thread-safe; a single instance
 * of each strategy is registered with {@link sh.pcx.packmerger.merge.PackMergeEngine}
 * at construction time.</p>
 */
public interface MergeStrategy {

    /**
     * Regex fragment for an optional overlay directory in front of {@code assets/}.
     * Overlays ({@code pack.mcmeta overlays.entries[].directory}) mirror the pack
     * root, so {@code <overlay>/assets/<ns>/models/x.json} needs the same merge
     * semantics as {@code assets/<ns>/models/x.json}. Strategies prepend this to
     * their {@code assets/...} patterns.
     */
    String OVERLAY_PREFIX = "(?:[^/]+/)?";

    /**
     * @param path the normalized pack-relative file path (e.g. {@code assets/minecraft/models/item/iron_sword.json})
     * @return {@code true} if this strategy should handle the given path
     */
    boolean matches(String path);

    /**
     * Merges two JSON objects representing the same file from two different packs.
     *
     * @param high the higher-priority pack's JSON (wins on primitive/object conflicts)
     * @param low  the lower-priority pack's JSON (contributes non-conflicting data)
     * @param ctx  per-call context for path-scoped logging; strategies that don't
     *             need to emit diagnostics may ignore it
     * @return a new merged {@link JsonObject} — neither input is modified
     */
    JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx);

    /**
     * @return a short identifier used in debug logs (e.g. {@code "model"}, {@code "atlas"})
     */
    String name();
}
