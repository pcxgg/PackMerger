package sh.pcx.packmerger.merge;

import java.util.Arrays;
import java.util.List;

/**
 * Lookup table from Minecraft version → expected resource-pack {@code pack_format}.
 *
 * <p>Mojang bumps the pack format whenever the resource-pack schema changes in a
 * backwards-incompatible way. A pack targeting a format higher than the server
 * renders assets against a schema the server doesn't understand — missing-texture
 * and missing-model crashes at runtime. A pack targeting a format much lower
 * also breaks: newer clients silently reject old overlays and models.</p>
 *
 * <p>Source: <a href="https://minecraft.wiki/w/Pack_format">minecraft.wiki — Pack format</a>.
 * Update this table when Mojang ships a new MC version; there's no way to derive
 * the mapping at runtime.</p>
 *
 * <p>Entries are ordered newest-to-oldest so {@link #forMinecraftVersion} returns
 * the first prefix match — callers passing "1.21.4" will match the 1.21.4+ entry
 * before reaching the 1.21 baseline.</p>
 */
public final class PackFormatRegistry {

    private PackFormatRegistry() {}

    /**
     * A mapping from a Minecraft version prefix to its resource-pack format.
     * The prefix is matched against the start of the server version string.
     */
    public record Entry(String minecraftPrefix, int packFormat) {}

    /** Registry table, newest first so prefix matching picks the most specific entry. */
    private static final List<Entry> TABLE = List.of(
            // 2026+ year-based scheme: <year>.<drop>[.<patch>]. Mojang switched
            // from 1.21.x to this after 1.21.11.
            // Values read from each Paper server jar's version.json (pack_version.resource_major).
            new Entry("26.3",    97),
            new Entry("26.2",    88),
            new Entry("26.1",    84),
            // Tail of the 1.21 line. Pack format gained minor versions in 1.21.9
            // (69.0); we track the integer major, which is what pack.mcmeta
            // declares and what the drift check compares.
            new Entry("1.21.11", 75),
            new Entry("1.21.10", 69),
            new Entry("1.21.9",  69),
            new Entry("1.21.8",  64),
            new Entry("1.21.7",  64),
            new Entry("1.21.6", 63),
            new Entry("1.21.5", 55),
            new Entry("1.21.4", 46),
            new Entry("1.21.3", 42),
            new Entry("1.21.2", 42),
            new Entry("1.21.1", 34),
            new Entry("1.21",   34),
            new Entry("1.20.6", 32),
            new Entry("1.20.5", 32),
            new Entry("1.20.4", 22),
            new Entry("1.20.3", 22),
            new Entry("1.20.2", 18),
            new Entry("1.20.1", 15),
            new Entry("1.20",   15),
            new Entry("1.19.4", 13),
            new Entry("1.19.3", 12),
            new Entry("1.19",   9)
    );

    /**
     * @param mcVersion the running Minecraft version (e.g. {@code "1.21.4"} from
     *                  {@code Bukkit.getMinecraftVersion()})
     * @return the expected pack_format for that version, or {@code -1} if the
     *         version isn't in the registry
     */
    public static int forMinecraftVersion(String mcVersion) {
        if (mcVersion == null) return -1;
        for (Entry e : TABLE) {
            if (mcVersion.startsWith(e.minecraftPrefix())) {
                return e.packFormat();
            }
        }
        return -1;
    }

    /**
     * Classifies the severity of the gap between a pack's declared format and
     * the server's expected format.
     */
    public enum Drift {
        /** Exact match — pack will load correctly. */
        MATCH,
        /** Pack targets a slightly older or newer format; may load with rendering
         *  quirks. Warranted as a warning. */
        MINOR,
        /** Pack targets a format gap large enough that many assets will break.
         *  Warranted as an error. */
        MAJOR,
        /** Server version isn't in the registry — we can't check. */
        UNKNOWN
    }

    /** Minor-drift threshold: packs within this many format numbers are treated as a warning. */
    public static final int MINOR_DRIFT_THRESHOLD = 1;

    /**
     * @param packFormat       the {@code pack_format} declared by a pack.mcmeta
     * @param supportedFormats the optional {@code supported_formats} range, or
     *                         an empty list / {@code null} if the pack didn't
     *                         declare one. If supplied (as [min, max]), the
     *                         server's expected format falling inside the range
     *                         is treated as a MATCH regardless of {@code packFormat}.
     * @param mcVersion        the running Minecraft version
     */
    public static Drift classify(int packFormat, int[] supportedFormats, String mcVersion) {
        int expected = forMinecraftVersion(mcVersion);
        if (expected < 0) return Drift.UNKNOWN;

        // If the pack declares a supported range, honor it first.
        if (supportedFormats != null && supportedFormats.length == 2
                && expected >= supportedFormats[0] && expected <= supportedFormats[1]) {
            return Drift.MATCH;
        }

        int gap = Math.abs(packFormat - expected);
        if (gap == 0) return Drift.MATCH;
        if (gap <= MINOR_DRIFT_THRESHOLD) return Drift.MINOR;
        return Drift.MAJOR;
    }

    /** @return a human-readable summary of the registry, mostly for diagnostics. */
    public static String describe() {
        return "PackFormatRegistry: " + TABLE.size() + " entries ("
                + TABLE.get(0).minecraftPrefix() + " … " + TABLE.get(TABLE.size() - 1).minecraftPrefix() + ")";
    }

    /** Used by tests. */
    static List<Entry> registry() { return Arrays.asList(TABLE.toArray(new Entry[0])); }
}
