package sh.pcx.packmerger.merge.strategy;

import java.util.function.Consumer;

/**
 * Per-call context passed to {@link MergeStrategy#merge} so strategies can emit
 * path-scoped warnings and notes without holding engine references.
 *
 * <p>Uses {@link Consumer} sinks rather than a concrete logger type so tests
 * can capture output without pulling the Bukkit/Adventure runtime onto the
 * test classpath. The engine wires both sinks to the console log and to the
 * merge provenance, so whatever a strategy reports here also shows up in
 * {@code /pm inspect} and the provenance sidecar.</p>
 *
 * @param path        the normalized pack-relative path of the file being merged
 * @param highPack    name of the higher-priority pack, or {@code null} if unknown
 * @param lowPack     name of the lower-priority pack (the current winner of the
 *                    path before this merge), or {@code null} if unknown
 * @param warningSink where warnings are delivered; may be {@code null} to discard
 * @param noteSink    where informational notes (automatic rewrites) are delivered;
 *                    may be {@code null} to discard
 */
public record MergeContext(String path, String highPack, String lowPack,
                           Consumer<String> warningSink, Consumer<String> noteSink) {

    /** Context without pack names or a note sink (tests, cluster staging). */
    public MergeContext(String path, Consumer<String> warningSink) {
        this(path, null, null, warningSink, null);
    }

    /**
     * Emits a warning through the attached sink, or silently drops it if the
     * sink is absent (e.g. in unit tests that don't assert on log output).
     */
    public void warn(String message) {
        if (warningSink != null) warningSink.accept(message);
    }

    /**
     * Records an informational note, e.g. an automatic rewrite the strategy made
     * so the merged file stays loadable. Dropped silently when no sink is attached.
     */
    public void note(String message) {
        if (noteSink != null) noteSink.accept(message);
    }

    /** @return the higher-priority pack's name for messages, or a generic label */
    public String highLabel() {
        return highPack != null ? highPack : "the higher-priority pack";
    }

    /** @return the lower-priority pack's name for messages, or a generic label */
    public String lowLabel() {
        return lowPack != null ? lowPack : "the lower-priority pack";
    }
}
