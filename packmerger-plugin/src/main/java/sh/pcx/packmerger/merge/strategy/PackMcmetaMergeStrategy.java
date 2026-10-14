package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.Map;

/**
 * Merges the root {@code pack.mcmeta} file.
 *
 * <p>Most of the file is a plain metadata object (the {@code pack} block with
 * {@code pack_format}, {@code description}, {@code supported_formats}) and is
 * handled by a standard high-priority-wins deep merge. Two nested arrays get
 * special handling because a naive object-only deep merge silently drops one
 * side's entries:</p>
 *
 * <ul>
 *   <li>{@code overlays.entries} — packs like QualityArmory use Minecraft's
 *       native overlay system to ship version-specific assets and declare the
 *       overlay directories here. Concatenated and deduped by {@code directory}.</li>
 *   <li>{@code filter.block} — declares namespace/path pairs that should be
 *       hidden from the client. When two packs both filter vanilla assets,
 *       one side's filters would otherwise be lost. Concatenated and deduped
 *       by the {@code namespace + "|" + path} tuple.</li>
 * </ul>
 *
 * <p>Two repairs keep the merged metadata loadable (each is recorded as a note in
 * the merge provenance):</p>
 *
 * <ul>
 *   <li><strong>Format range.</strong> When either pack declares a range
 *       ({@code min_format}/{@code max_format} or {@code supported_formats}), the
 *       merged pack declares the union of both packs' ranges, written in the shape
 *       the client expects: {@code min_format}/{@code max_format} when the range
 *       reaches past format {@value #LAST_LEGACY_FORMAT}, plus
 *       {@code supported_formats} and an in-range {@code pack_format} when it
 *       reaches format {@value #LAST_LEGACY_FORMAT} or below. A deep merge would
 *       otherwise mix one pack's {@code pack_format} with the other's range.</li>
 *   <li><strong>Overlay formats.</strong> Since 1.21.9 the client requires every
 *       overlay entry to declare {@code formats} once any entry reaches format
 *       {@value #LAST_LEGACY_FORMAT} or below. Two overlay lists that are valid on
 *       their own can break that rule once concatenated, so entries that only
 *       declare {@code min_format}/{@code max_format} gain the equivalent
 *       {@code formats} range.</li>
 * </ul>
 */
public class PackMcmetaMergeStrategy implements MergeStrategy {

    private static final String OVERLAYS_KEY = "overlays";
    private static final String ENTRIES_KEY = "entries";
    private static final String DIRECTORY_KEY = "directory";

    private static final String FILTER_KEY = "filter";
    private static final String BLOCK_KEY = "block";
    private static final String NAMESPACE_KEY = "namespace";
    private static final String PATH_KEY = "path";

    private static final String PACK_KEY = "pack";
    private static final String PACK_FORMAT = "pack_format";
    private static final String SUPPORTED_FORMATS = "supported_formats";
    private static final String MIN_FORMAT = "min_format";
    private static final String MAX_FORMAT = "max_format";
    private static final String FORMATS = "formats";

    /**
     * The last resource-pack format of clients that read {@code pack_format} /
     * {@code supported_formats} / overlay {@code formats} (1.21.8). Newer clients
     * read {@code min_format}/{@code max_format}.
     */
    static final int LAST_LEGACY_FORMAT = 64;

    @Override
    public boolean matches(String path) {
        return path.equalsIgnoreCase("pack.mcmeta");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        JsonObject highWithout = copyWithout(high, OVERLAYS_KEY, FILTER_KEY);
        JsonObject lowWithout = copyWithout(low, OVERLAYS_KEY, FILTER_KEY);
        JsonObject result = JsonMerger.deepMergeObjects(highWithout, lowWithout);
        normalizeFormatRange(result, high, low, ctx);

        JsonObject mergedOverlays = mergeOverlays(high, low, ctx);
        if (mergedOverlays != null) {
            result.add(OVERLAYS_KEY, mergedOverlays);
        }

        JsonObject mergedFilter = mergeFilter(high, low);
        if (mergedFilter != null) {
            result.add(FILTER_KEY, mergedFilter);
        }

        return result;
    }

    @Override
    public String name() {
        return "pack_mcmeta";
    }

    private static JsonObject mergeOverlays(JsonObject high, JsonObject low, MergeContext ctx) {
        JsonObject highOverlays = getObjectOrNull(high, OVERLAYS_KEY);
        JsonObject lowOverlays = getObjectOrNull(low, OVERLAYS_KEY);
        if (highOverlays == null && lowOverlays == null) return null;

        JsonObject highNoEntries = highOverlays == null ? new JsonObject() : copyWithout(highOverlays, ENTRIES_KEY);
        JsonObject lowNoEntries = lowOverlays == null ? new JsonObject() : copyWithout(lowOverlays, ENTRIES_KEY);
        JsonObject merged = JsonMerger.deepMergeObjects(highNoEntries, lowNoEntries);

        JsonArray highEntries = getArrayOrEmpty(highOverlays, ENTRIES_KEY);
        JsonArray lowEntries = getArrayOrEmpty(lowOverlays, ENTRIES_KEY);
        if (highEntries.size() > 0 || lowEntries.size() > 0) {
            JsonArray mergedEntries = JsonMerger.concatArraysWithDedup(highEntries, lowEntries, PackMcmetaMergeStrategy::entryIdentity);
            if (highEntries.size() > 0 && lowEntries.size() > 0) {
                int completed = completeOverlayFormats(mergedEntries);
                if (completed > 0) {
                    ctx.note("pack.mcmeta: added a 'formats' range to " + completed + " overlay entr"
                            + (completed == 1 ? "y" : "ies") + " so the combined overlay list loads on "
                            + "clients up to format " + LAST_LEGACY_FORMAT);
                }
            }
            merged.add(ENTRIES_KEY, mergedEntries);
        }
        return merged;
    }

    private static JsonObject mergeFilter(JsonObject high, JsonObject low) {
        JsonObject highFilter = getObjectOrNull(high, FILTER_KEY);
        JsonObject lowFilter = getObjectOrNull(low, FILTER_KEY);
        if (highFilter == null && lowFilter == null) return null;

        JsonObject highNoBlock = highFilter == null ? new JsonObject() : copyWithout(highFilter, BLOCK_KEY);
        JsonObject lowNoBlock = lowFilter == null ? new JsonObject() : copyWithout(lowFilter, BLOCK_KEY);
        JsonObject merged = JsonMerger.deepMergeObjects(highNoBlock, lowNoBlock);

        JsonArray highBlock = getArrayOrEmpty(highFilter, BLOCK_KEY);
        JsonArray lowBlock = getArrayOrEmpty(lowFilter, BLOCK_KEY);
        if (highBlock.size() > 0 || lowBlock.size() > 0) {
            JsonArray mergedBlock = JsonMerger.concatArraysWithDedup(highBlock, lowBlock, PackMcmetaMergeStrategy::filterBlockIdentity);
            merged.add(BLOCK_KEY, mergedBlock);
        }
        return merged;
    }

    /**
     * Dedup key for an overlay entry, based on its {@code directory}. Entries
     * without a usable directory fall back to their full string form so malformed
     * or unrecognized entries are never silently merged together.
     */
    private static String entryIdentity(JsonElement element) {
        if (!element.isJsonObject()) return element.toString();
        JsonObject obj = element.getAsJsonObject();
        if (obj.has(DIRECTORY_KEY) && obj.get(DIRECTORY_KEY).isJsonPrimitive()) {
            return obj.get(DIRECTORY_KEY).getAsString();
        }
        return element.toString();
    }

    /**
     * Dedup key for a filter.block entry, based on its {@code namespace + "|" + path}
     * tuple. Either side is optional in the pack.mcmeta schema (an entry with only
     * {@code path} filters all namespaces); entries with neither field fall back to
     * their raw string form to avoid silently merging unrelated malformed entries.
     */
    private static String filterBlockIdentity(JsonElement element) {
        if (!element.isJsonObject()) return element.toString();
        JsonObject obj = element.getAsJsonObject();
        String ns = stringOrNull(obj, NAMESPACE_KEY);
        String path = stringOrNull(obj, PATH_KEY);
        if (ns == null && path == null) return element.toString();
        return (ns == null ? "" : ns) + "|" + (path == null ? "" : path);
    }

    private static String stringOrNull(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    // ---------------------------------------------------------------------
    // Format range repair
    // ---------------------------------------------------------------------

    /** One end of a format range: the major version, plus the JSON the author wrote for it. */
    record Bound(int major, JsonElement raw) {}

    /** A pack's declared format range, inclusive. */
    record Range(Bound min, Bound max) {}

    /**
     * Rewrites {@code result.pack}'s format fields to the union of both packs'
     * declared ranges. No-op unless at least one side declares a range, so two
     * packs with only {@code pack_format} keep the high-priority value.
     */
    static void normalizeFormatRange(JsonObject result, JsonObject high, JsonObject low, MergeContext ctx) {
        JsonObject highPack = getObjectOrNull(high, PACK_KEY);
        JsonObject lowPack = getObjectOrNull(low, PACK_KEY);
        JsonObject pack = getObjectOrNull(result, PACK_KEY);
        if (pack == null || highPack == null || lowPack == null) return;
        if (!declaresRange(highPack) && !declaresRange(lowPack)) return;

        Range highRange = readRange(highPack);
        Range lowRange = readRange(lowPack);
        if (highRange == null && lowRange == null) return;
        Range union = highRange == null ? lowRange
                : lowRange == null ? highRange
                : new Range(
                        lowRange.min().major() < highRange.min().major() ? lowRange.min() : highRange.min(),
                        lowRange.max().major() > highRange.max().major() ? lowRange.max() : highRange.max());

        JsonObject before = pack.deepCopy();
        int lo = union.min().major();
        int hi = union.max().major();
        if (hi > LAST_LEGACY_FORMAT) {
            pack.add(MIN_FORMAT, union.min().raw().deepCopy());
            pack.add(MAX_FORMAT, union.max().raw().deepCopy());
            if (lo <= LAST_LEGACY_FORMAT) {
                pack.add(SUPPORTED_FORMATS, intPair(lo, LAST_LEGACY_FORMAT));
                clampPackFormat(pack, lo, LAST_LEGACY_FORMAT, lo);
            } else {
                pack.remove(SUPPORTED_FORMATS);
                if (pack.has(PACK_FORMAT)) clampPackFormat(pack, lo, hi, lo);
            }
        } else {
            pack.remove(MIN_FORMAT);
            pack.remove(MAX_FORMAT);
            pack.add(SUPPORTED_FORMATS, intPair(lo, hi));
            clampPackFormat(pack, lo, hi, hi);
        }

        if (!pack.equals(before)) {
            ctx.note("pack.mcmeta: format declaration rewritten to cover " + lo + ".." + hi
                    + " (the union of " + ctx.highLabel() + " and " + ctx.lowLabel() + ")");
        }
    }

    private static boolean declaresRange(JsonObject pack) {
        return pack.has(MIN_FORMAT) || pack.has(MAX_FORMAT) || pack.has(SUPPORTED_FORMATS);
    }

    /** Reads a pack block's range from min/max_format, supported_formats or pack_format. */
    static Range readRange(JsonObject pack) {
        Integer packFormat = readMajor(pack.get(PACK_FORMAT));
        int[] supported = readIntRange(pack.get(SUPPORTED_FORMATS));
        if (pack.has(MIN_FORMAT) || pack.has(MAX_FORMAT)) {
            Bound min = readBound(pack.get(MIN_FORMAT));
            Bound max = readBound(pack.get(MAX_FORMAT));
            if (min == null) {
                Integer m = supported != null ? Integer.valueOf(supported[0]) : packFormat;
                min = m != null ? new Bound(m, new JsonPrimitive(m)) : max;
            }
            if (max == null) {
                Integer m = supported != null ? Integer.valueOf(supported[1]) : packFormat;
                max = m != null ? new Bound(m, new JsonPrimitive(m)) : min;
            }
            if (min != null && max != null) {
                return min.major() <= max.major() ? new Range(min, max) : new Range(max, min);
            }
        }
        if (supported != null) {
            return new Range(new Bound(supported[0], new JsonPrimitive(supported[0])),
                    new Bound(supported[1], new JsonPrimitive(supported[1])));
        }
        if (packFormat != null) {
            Bound b = new Bound(packFormat, new JsonPrimitive(packFormat));
            return new Range(b, b);
        }
        return null;
    }

    private static void clampPackFormat(JsonObject pack, int lo, int hi, int fallback) {
        Integer current = readMajor(pack.get(PACK_FORMAT));
        if (current == null || current < lo || current > hi) {
            pack.addProperty(PACK_FORMAT, fallback);
        }
    }

    /**
     * Gives every overlay entry that lacks {@code formats} the range its
     * {@code min_format}/{@code max_format} describe, if any entry in the list
     * reaches format {@link #LAST_LEGACY_FORMAT} or below (only then do clients
     * require it). Entries that already declare {@code formats}, or whose bounds
     * do not parse, are left exactly as written.
     *
     * @return how many entries were completed
     */
    static int completeOverlayFormats(JsonArray entries) {
        boolean required = false;
        for (JsonElement el : entries) {
            if (!el.isJsonObject()) continue;
            Integer min = effectiveOverlayMin(el.getAsJsonObject());
            if (min != null && min <= LAST_LEGACY_FORMAT) {
                required = true;
                break;
            }
        }
        if (!required) return 0;

        int completed = 0;
        for (int i = 0; i < entries.size(); i++) {
            JsonElement el = entries.get(i);
            if (!el.isJsonObject() || el.getAsJsonObject().has(FORMATS)) continue;
            JsonObject entry = el.getAsJsonObject();
            Integer min = readMajor(entry.get(MIN_FORMAT));
            Integer max = readMajor(entry.get(MAX_FORMAT));
            if (min == null || max == null || min > max) continue;
            JsonObject formats = new JsonObject();
            formats.addProperty("min_inclusive", min);
            formats.addProperty("max_inclusive", min <= LAST_LEGACY_FORMAT ? Math.min(max, LAST_LEGACY_FORMAT) : max);
            JsonObject copy = entry.deepCopy();
            copy.add(FORMATS, formats);
            entries.set(i, copy);
            completed++;
        }
        return completed;
    }

    /** The lowest format an overlay entry applies to: the lower of min_format and formats. */
    private static Integer effectiveOverlayMin(JsonObject entry) {
        Integer min = readMajor(entry.get(MIN_FORMAT));
        int[] formats = readIntRange(entry.get(FORMATS));
        if (min == null) return formats == null ? null : formats[0];
        return formats == null ? min : Math.min(min, formats[0]);
    }

    private static Bound readBound(JsonElement el) {
        Integer major = readMajor(el);
        return major == null ? null : new Bound(major, el);
    }

    /** A format number: an int, or a {@code [major, minor]} array (major is used). */
    private static Integer readMajor(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) return el.getAsInt();
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (!arr.isEmpty() && arr.get(0).isJsonPrimitive() && arr.get(0).getAsJsonPrimitive().isNumber()) {
                return arr.get(0).getAsInt();
            }
        }
        return null;
    }

    /** A {@code supported_formats}/{@code formats} value: int, {@code [min, max]} or {@code {min_inclusive, max_inclusive}}. */
    private static int[] readIntRange(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        try {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                int n = el.getAsInt();
                return new int[] { n, n };
            }
            if (el.isJsonArray() && el.getAsJsonArray().size() == 2) {
                JsonArray arr = el.getAsJsonArray();
                return new int[] { arr.get(0).getAsInt(), arr.get(1).getAsInt() };
            }
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("min_inclusive") && obj.has("max_inclusive")) {
                    return new int[] { obj.get("min_inclusive").getAsInt(), obj.get("max_inclusive").getAsInt() };
                }
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        return null;
    }

    private static JsonArray intPair(int a, int b) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        return arr;
    }

    private static JsonObject getObjectOrNull(JsonObject source, String key) {
        if (source == null || !source.has(key)) return null;
        JsonElement element = source.get(key);
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray getArrayOrEmpty(JsonObject source, String key) {
        if (source == null || !source.has(key)) return new JsonArray();
        JsonElement element = source.get(key);
        return element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject copyWithout(JsonObject source, String... excludedKeys) {
        JsonObject copy = new JsonObject();
        outer:
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            for (String excluded : excludedKeys) {
                if (entry.getKey().equals(excluded)) continue outer;
            }
            copy.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return copy;
    }
}
