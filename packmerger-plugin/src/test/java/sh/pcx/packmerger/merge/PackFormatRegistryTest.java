package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PackFormatRegistryTest {

    @Test
    void forMinecraftVersion_knownPrefixes_returnMappedFormat() {
        assertEquals(46, PackFormatRegistry.forMinecraftVersion("1.21.4"));
        assertEquals(55, PackFormatRegistry.forMinecraftVersion("1.21.5"));
        assertEquals(34, PackFormatRegistry.forMinecraftVersion("1.21"));
        assertEquals(34, PackFormatRegistry.forMinecraftVersion("1.21.1"));
    }

    @Test
    void forMinecraftVersion_longestPrefixWins() {
        // 1.21.4 must match its specific entry (46), not 1.21's (34).
        assertEquals(46, PackFormatRegistry.forMinecraftVersion("1.21.4"));
        assertEquals(42, PackFormatRegistry.forMinecraftVersion("1.21.2"));
        // 1.21.11 must match its own entry (75), not 1.21.1's (34) — both are
        // prefixes of "1.21.11", so newest-first ordering is what saves us.
        assertEquals(75, PackFormatRegistry.forMinecraftVersion("1.21.11"));
        assertEquals(69, PackFormatRegistry.forMinecraftVersion("1.21.10"));
    }

    @Test
    void forMinecraftVersion_lateOneTwentyOneLine() {
        assertEquals(64, PackFormatRegistry.forMinecraftVersion("1.21.7"));
        assertEquals(64, PackFormatRegistry.forMinecraftVersion("1.21.8"));
        assertEquals(69, PackFormatRegistry.forMinecraftVersion("1.21.9"));
        assertEquals(75, PackFormatRegistry.forMinecraftVersion("1.21.11"));
    }

    @Test
    void forMinecraftVersion_yearBasedScheme() {
        // The 2026 switch from 1.21.x to <year>.<drop> must resolve, otherwise
        // the pack-format guardrail silently no-ops on every modern server.
        assertEquals(84, PackFormatRegistry.forMinecraftVersion("26.1"));
        assertEquals(84, PackFormatRegistry.forMinecraftVersion("26.1.2"));
        assertEquals(88, PackFormatRegistry.forMinecraftVersion("26.2"));
        assertEquals(97, PackFormatRegistry.forMinecraftVersion("26.3"));
    }

    @Test
    void classify_yearBasedScheme_notUnknown() {
        // A matching pack on a 26.1.2 server must classify as MATCH, not UNKNOWN.
        assertEquals(PackFormatRegistry.Drift.MATCH,
                PackFormatRegistry.classify(84, null, "26.1.2"));
    }

    @Test
    void forMinecraftVersion_unknown_returnsNegativeOne() {
        assertEquals(-1, PackFormatRegistry.forMinecraftVersion("1.22.0"));
        assertEquals(-1, PackFormatRegistry.forMinecraftVersion("1.18.2"));
        assertEquals(-1, PackFormatRegistry.forMinecraftVersion(null));
    }

    @Test
    void classify_matchOnExactFormat() {
        assertEquals(PackFormatRegistry.Drift.MATCH,
                PackFormatRegistry.classify(46, null, "1.21.4"));
    }

    @Test
    void classify_minorDriftOneOff() {
        assertEquals(PackFormatRegistry.Drift.MINOR,
                PackFormatRegistry.classify(45, null, "1.21.4"));
        assertEquals(PackFormatRegistry.Drift.MINOR,
                PackFormatRegistry.classify(47, null, "1.21.4"));
    }

    @Test
    void classify_majorDriftLarge() {
        assertEquals(PackFormatRegistry.Drift.MAJOR,
                PackFormatRegistry.classify(34, null, "1.21.4")); // 12-wide gap
        assertEquals(PackFormatRegistry.Drift.MAJOR,
                PackFormatRegistry.classify(63, null, "1.21.4")); // 17-wide gap
    }

    @Test
    void classify_unknownServerVersion_returnsUnknown() {
        assertEquals(PackFormatRegistry.Drift.UNKNOWN,
                PackFormatRegistry.classify(46, null, "1.22.0"));
    }

    @Test
    void classify_supportedFormatsRangeCoversExpected_returnsMatch() {
        // Pack declares pack_format: 34 (old) but supported_formats: [34, 50]
        // spans the expected 46 → treat as MATCH.
        assertEquals(PackFormatRegistry.Drift.MATCH,
                PackFormatRegistry.classify(34, new int[]{34, 50}, "1.21.4"));
    }

    @Test
    void classify_supportedFormatsRangeMissesExpected_classifiesByPackFormat() {
        // Range [10, 30] does not include 46 → fall through to pack_format comparison.
        assertEquals(PackFormatRegistry.Drift.MAJOR,
                PackFormatRegistry.classify(20, new int[]{10, 30}, "1.21.4"));
    }
}
