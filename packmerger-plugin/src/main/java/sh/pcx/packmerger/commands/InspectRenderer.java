package sh.pcx.packmerger.commands;

import sh.pcx.packmerger.merge.MergeProvenance;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pure renderer that turns {@link MergeProvenance} into human-readable MiniMessage
 * lines for the {@code /pm inspect} command family.
 *
 * <p>Kept dependency-free (no Bukkit, no Adventure runtime) so the rendering
 * logic can be unit-tested without a server fixture. Consumers deserialize the
 * returned strings through MiniMessage on the command side.</p>
 */
public final class InspectRenderer {

    private InspectRenderer() {}

    /** Output limit for dense views — inspect truncates to this many lines and prints a pointer. */
    public static final int MAX_CHAT_LINES = 25;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Top-line summary: pack order, per-pack counts, collision/merged counts, time. */
    public static List<String> summary(MergeProvenance prov) {
        List<String> out = new ArrayList<>();
        if (prov == null) {
            out.add("<red>No merge has completed yet — run <white>/pm reload</white> first.</red>");
            return out;
        }

        out.add("<aqua>━━━━━━━━━━ PackMerger inspect ━━━━━━━━━━</aqua>");
        out.add("<gray>Merged at:</gray> <white>" + TS.format(prov.mergedAt().atZone(ZoneId.systemDefault())) + "</white>");
        out.add("<gray>Pack order (highest priority first):</gray>");
        for (int i = 0; i < prov.packOrder().size(); i++) {
            out.add("  <yellow>" + (i + 1) + ".</yellow> <white>" + prov.packOrder().get(i) + "</white>");
        }

        out.add("<gray>Files won per pack:</gray>");
        Map<String, Long> byWinner = prov.filesByWinner();
        byWinner.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> out.add("  <white>" + pad(e.getKey(), 32) + "</white> <aqua>"
                        + e.getValue() + "</aqua> file" + (e.getValue() == 1 ? "" : "s")));

        int total = prov.files().size();
        int collisions = prov.collisionCount();
        int merged = prov.mergedCount();
        out.add("<gray>Total files:</gray> <white>" + total + "</white>"
                + ("<gray>, collisions:</gray> <yellow>" + collisions + "</yellow>")
                + ("<gray>, JSON merged:</gray> <green>" + merged + "</green>"));

        out.add("<gray>For per-pack detail: <white>/pm inspect &lt;pack&gt;</white></gray>");
        out.add("<gray>For collision list: <white>/pm inspect collisions</white></gray>");
        out.add("<gray>To write full report to disk: <white>/pm inspect export</white></gray>");
        return out;
    }

    /** Per-pack view: files this pack won, files it contributed to but lost on. */
    public static List<String> packDetail(MergeProvenance prov, String packName) {
        List<String> out = new ArrayList<>();
        if (prov == null) {
            out.add("<red>No merge has completed yet.</red>");
            return out;
        }
        if (!prov.packOrder().contains(packName)) {
            out.add("<red>Pack <white>" + packName + "</white> was not in the last merge.</red>");
            out.add("<gray>Known packs:</gray> <white>" + String.join(", ", prov.packOrder()) + "</white>");
            return out;
        }

        List<String> wonPaths = new ArrayList<>();
        List<String> overriddenPaths = new ArrayList<>();
        for (MergeProvenance.FileRecord rec : prov.files().values()) {
            if (rec.winner().equals(packName)) {
                wonPaths.add(rec.path());
            } else if (rec.contributors().contains(packName)) {
                overriddenPaths.add(rec.path() + " → " + rec.winner());
            }
        }

        out.add("<aqua>━━━ Inspect: " + packName + " ━━━</aqua>");
        out.add("<gray>Won:</gray> <white>" + wonPaths.size() + "</white>"
                + " <gray>  Contributed but overridden:</gray> <white>" + overriddenPaths.size() + "</white>");

        if (!wonPaths.isEmpty()) {
            out.add("<green>Files won by this pack:</green>");
            addTruncated(out, wonPaths, "  <white>", "</white>");
        }
        if (!overriddenPaths.isEmpty()) {
            out.add("<yellow>Contributed but overridden by higher-priority pack:</yellow>");
            addTruncated(out, overriddenPaths, "  <gray>", "</gray>");
        }
        return out;
    }

    /** Collisions-only view: every file where two or more packs touched the same path. */
    public static List<String> collisions(MergeProvenance prov) {
        List<String> out = new ArrayList<>();
        if (prov == null) {
            out.add("<red>No merge has completed yet.</red>");
            return out;
        }

        List<MergeProvenance.FileRecord> collisions = new ArrayList<>();
        for (MergeProvenance.FileRecord rec : prov.files().values()) {
            if (rec.isCollision()) collisions.add(rec);
        }
        // Order: more contributors first, then alphabetical path
        collisions.sort(Comparator
                .comparingInt((MergeProvenance.FileRecord r) -> -r.contributors().size())
                .thenComparing(MergeProvenance.FileRecord::path));

        out.add("<aqua>━━━ Inspect: collisions ━━━</aqua>");
        out.add("<gray>" + collisions.size() + " file(s) contributed by two or more packs.</gray>");
        if (collisions.isEmpty()) return out;

        int shown = 0;
        for (MergeProvenance.FileRecord rec : collisions) {
            if (shown >= MAX_CHAT_LINES) {
                out.add("<gray>… " + (collisions.size() - shown) + " more. Use <white>/pm inspect export</white> for the full list.</gray>");
                break;
            }
            out.add("  <white>" + rec.path() + "</white> <gray>[" + rec.contributors().size() + " contributors]</gray>");
            out.add("    <yellow>winner:</yellow> <white>" + rec.winner() + "</white>"
                    + (rec.wasMerged() ? " <green>(strategy: " + rec.strategyName() + ")</green>" : ""));
            for (String note : rec.notes()) {
                out.add("    <gold>note:</gold> <gray>" + escape(note) + "</gray>");
            }
            shown++;
        }
        return out;
    }

    /** Full textual report suitable for writing to disk — no color tags, stable column layout. */
    public static List<String> fullReport(MergeProvenance prov) {
        List<String> out = new ArrayList<>();
        if (prov == null) {
            out.add("No merge has completed yet.");
            return out;
        }

        out.add("PackMerger merge report");
        out.add("=======================");
        out.add("Merged at: " + TS.format(prov.mergedAt().atZone(ZoneId.systemDefault())));
        out.add("");
        out.add("Pack order (highest priority first):");
        for (int i = 0; i < prov.packOrder().size(); i++) {
            out.add("  " + (i + 1) + ". " + prov.packOrder().get(i));
        }
        out.add("");

        out.add("Files won per pack:");
        prov.filesByWinner().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> out.add("  " + pad(e.getKey(), 40) + e.getValue()));
        out.add("");

        out.add("Files touched per pack (including overrides):");
        prov.filesByContributor().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> out.add("  " + pad(e.getKey(), 40) + e.getValue()));
        out.add("");

        out.add("Totals: " + prov.files().size() + " files, "
                + prov.collisionCount() + " collisions, "
                + prov.mergedCount() + " JSON-merged");
        out.add("");

        out.add("All files (sorted):");
        prov.files().values().stream()
                .sorted(Comparator.comparing(MergeProvenance.FileRecord::path))
                .forEach(rec -> {
                    StringBuilder line = new StringBuilder();
                    line.append("  ").append(rec.path())
                            .append(" -> ").append(rec.winner());
                    if (rec.contributors().size() > 1) {
                        line.append("  (contributors: ").append(String.join(", ", rec.contributors())).append(")");
                    }
                    if (rec.strategyName() != null) {
                        line.append(rec.wasMerged() ? "  [merged:" : "  [strategy:").append(rec.strategyName()).append("]");
                    }
                    out.add(line.toString());
                    for (String note : rec.notes()) {
                        out.add("      note: " + note);
                    }
                });
        return out;
    }

    /** Neutralises MiniMessage tags in free text (pack names and paths come from disk). */
    private static String escape(String text) {
        return text.replace("<", "\\<");
    }

    private static void addTruncated(List<String> out, List<String> items, String prefix, String suffix) {
        int shown = 0;
        for (String item : items) {
            if (shown >= MAX_CHAT_LINES) {
                out.add("<gray>… " + (items.size() - shown) + " more. Use <white>/pm inspect export</white> for the full list.</gray>");
                return;
            }
            out.add(prefix + item + suffix);
            shown++;
        }
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s + " ";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }
}
