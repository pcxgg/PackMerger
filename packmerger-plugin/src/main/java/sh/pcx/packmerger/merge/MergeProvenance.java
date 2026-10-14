package sh.pcx.packmerger.merge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Per-file record of which packs contributed to a merge output.
 *
 * <p>A {@link MergeProvenance} instance is produced alongside every successful
 * merge. For every path in the merged output it records the winning pack (what
 * the client ends up seeing), the full list of contributors in merge order
 * (lowest to highest priority), and — for JSON paths that go through a merge
 * strategy — the strategy name and whether the output was actually merged (as
 * opposed to a single-contributor pass-through).</p>
 *
 * <p>This is the data source for the {@code /pm inspect} command and the plugin
 * API's {@code getLastMergeProvenance()}. It is persisted to
 * {@code output/.merge-provenance.json} so restarts don't blank the state.</p>
 */
public final class MergeProvenance {

    /**
     * A single file's provenance: who wrote it last, who contributed, and
     * whether a JSON merge strategy was involved.
     *
     * @param path         the normalized pack-relative path (e.g. {@code assets/minecraft/models/item/iron_sword.json})
     * @param winner       the pack whose data ends up in the final output
     * @param contributors every pack that touched this path, in processing
     *                     order (lowest priority first; {@code winner} is
     *                     always the last element)
     * @param strategyName the {@link sh.pcx.packmerger.merge.strategy.MergeStrategy#name()}
     *                     used, or {@code null} if the file was handled as a
     *                     simple overwrite
     * @param wasMerged    {@code true} if two or more packs contributed through
     *                     a merge strategy; {@code false} otherwise (either a
     *                     single contributor, or a non-JSON overwrite)
     * @param notes        what the merge strategy reported for this file:
     *                     collisions it resolved (e.g. a CustomModelData or
     *                     item-definition threshold claimed by two packs) and
     *                     automatic rewrites (e.g. a completed overlay format
     *                     range); empty when there was nothing to report
     */
    public record FileRecord(
            String path,
            String winner,
            List<String> contributors,
            String strategyName,
            boolean wasMerged,
            List<String> notes) {

        public FileRecord {
            contributors = List.copyOf(contributors);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        /** Record without notes (pre-notes callers and older sidecars). */
        public FileRecord(String path, String winner, List<String> contributors,
                          String strategyName, boolean wasMerged) {
            this(path, winner, contributors, strategyName, wasMerged, List.of());
        }

        /** {@code true} if more than one pack contributed to this path. */
        public boolean isCollision() {
            return contributors.size() > 1;
        }
    }

    private final Map<String, FileRecord> files;
    private final List<String> packOrder;
    private final Instant mergedAt;

    public MergeProvenance(Map<String, FileRecord> files, List<String> packOrder, Instant mergedAt) {
        this.files = Map.copyOf(files);
        this.packOrder = List.copyOf(packOrder);
        this.mergedAt = mergedAt;
    }

    /** Path → {@link FileRecord}. Paths are the same keys used by the output zip. */
    public Map<String, FileRecord> files() { return files; }

    /** The merge order at the time of this merge (highest priority first). */
    public List<String> packOrder() { return packOrder; }

    /** Timestamp of the merge that produced this provenance. */
    public Instant mergedAt() { return mergedAt; }

    /** Count of files contributed by each pack (as winner). */
    public Map<String, Long> filesByWinner() {
        return files.values().stream()
                .collect(Collectors.groupingBy(FileRecord::winner, Collectors.counting()));
    }

    /** Count of files a pack contributed to (whether it won or not). */
    public Map<String, Long> filesByContributor() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String pack : packOrder) result.put(pack, 0L);
        for (FileRecord rec : files.values()) {
            for (String pack : rec.contributors()) {
                result.merge(pack, 1L, Long::sum);
            }
        }
        return result;
    }

    /** Files where two or more packs touched the same path. */
    public int collisionCount() {
        return (int) files.values().stream().filter(FileRecord::isCollision).count();
    }

    /** Files merged through a strategy (as opposed to simple overwrite). */
    public int mergedCount() {
        return (int) files.values().stream().filter(FileRecord::wasMerged).count();
    }

    /** Files that carry at least one strategy note (collision resolved or automatic rewrite). */
    public int notedCount() {
        return (int) files.values().stream().filter(r -> !r.notes().isEmpty()).count();
    }

    /** Pretty-printed JSON suitable for writing to disk. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("mergedAt", mergedAt.toString());

        JsonArray order = new JsonArray();
        packOrder.forEach(order::add);
        root.add("packOrder", order);

        JsonObject filesJson = new JsonObject();
        for (Map.Entry<String, FileRecord> e : files.entrySet()) {
            FileRecord rec = e.getValue();
            JsonObject recJson = new JsonObject();
            recJson.addProperty("winner", rec.winner());
            JsonArray contributors = new JsonArray();
            rec.contributors().forEach(contributors::add);
            recJson.add("contributors", contributors);
            if (rec.strategyName() != null) {
                recJson.addProperty("strategyName", rec.strategyName());
            }
            recJson.addProperty("wasMerged", rec.wasMerged());
            if (!rec.notes().isEmpty()) {
                JsonArray notes = new JsonArray();
                rec.notes().forEach(notes::add);
                recJson.add("notes", notes);
            }
            filesJson.add(e.getKey(), recJson);
        }
        root.add("files", filesJson);

        return GSON.toJson(root);
    }

    /** Parses a previously-serialized {@link MergeProvenance}; {@code null} on invalid input. */
    public static MergeProvenance fromJson(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();

            Instant mergedAt = Instant.parse(root.get("mergedAt").getAsString());

            List<String> packOrder = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("packOrder")) {
                packOrder.add(el.getAsString());
            }

            Map<String, FileRecord> files = new LinkedHashMap<>();
            JsonObject filesJson = root.getAsJsonObject("files");
            for (Map.Entry<String, JsonElement> e : filesJson.entrySet()) {
                JsonObject recJson = e.getValue().getAsJsonObject();
                String path = e.getKey();
                String winner = recJson.get("winner").getAsString();
                List<String> contributors = new ArrayList<>();
                for (JsonElement c : recJson.getAsJsonArray("contributors")) {
                    contributors.add(c.getAsString());
                }
                String strategyName = recJson.has("strategyName") && !recJson.get("strategyName").isJsonNull()
                        ? recJson.get("strategyName").getAsString() : null;
                boolean wasMerged = recJson.has("wasMerged") && recJson.get("wasMerged").getAsBoolean();
                List<String> notes = new ArrayList<>();
                if (recJson.has("notes") && recJson.get("notes").isJsonArray()) {
                    for (JsonElement n : recJson.getAsJsonArray("notes")) notes.add(n.getAsString());
                }
                files.put(path, new FileRecord(path, winner, contributors, strategyName, wasMerged, notes));
            }

            return new MergeProvenance(files, packOrder, mergedAt);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * Mutable accumulator used by {@link PackMergeEngine} during a merge pass;
     * frozen into an immutable {@link MergeProvenance} by {@link #build(List, Instant)}.
     */
    public static final class Builder {
        private final Map<String, MutableRecord> records = new LinkedHashMap<>();

        /**
         * Record that {@code pack} just contributed {@code path}.
         *
         * @param strategyName strategy used, or {@code null} for a plain overwrite
         * @param wasMerged    {@code true} if the strategy actually combined two
         *                     sources (i.e. not the first-contributor path)
         */
        public void record(String path, String pack, String strategyName, boolean wasMerged) {
            MutableRecord rec = records.computeIfAbsent(path, p -> new MutableRecord());
            rec.contributors.add(pack);
            rec.winner = pack;
            if (strategyName != null) {
                rec.strategyName = strategyName;
                if (wasMerged) rec.wasMerged = true;
            }
        }

        /**
         * @return the pack that currently owns {@code path} (the last one recorded),
         *         or {@code null} if nothing has contributed it yet
         */
        public String winnerOf(String path) {
            MutableRecord rec = records.get(path);
            return rec == null ? null : rec.winner;
        }

        /** Attach a strategy note (resolved collision or automatic rewrite) to {@code path}. */
        public void note(String path, String message) {
            records.computeIfAbsent(path, p -> new MutableRecord()).notes.add(message);
        }

        /** Remove a path — used by the engine when a file is overridden by a packs-folder custom file. */
        public void remove(String path) {
            records.remove(path);
        }

        /** Record that an externally-sourced file (custom pack.mcmeta / pack.png, generated default) now owns this path. */
        public void recordExternal(String path, String source) {
            MutableRecord rec = new MutableRecord();
            rec.contributors.add(source);
            rec.winner = source;
            records.put(path, rec);
        }

        public MergeProvenance build(List<String> packOrder, Instant mergedAt) {
            Map<String, FileRecord> frozen = new LinkedHashMap<>();
            for (Map.Entry<String, MutableRecord> e : records.entrySet()) {
                String path = e.getKey();
                MutableRecord m = e.getValue();
                frozen.put(path, new FileRecord(
                        path,
                        m.winner,
                        Collections.unmodifiableList(new ArrayList<>(m.contributors)),
                        m.strategyName,
                        m.wasMerged,
                        m.notes));
            }
            return new MergeProvenance(frozen, packOrder, mergedAt);
        }

        private static final class MutableRecord {
            String winner;
            final List<String> contributors = new ArrayList<>();
            String strategyName;
            boolean wasMerged;
            final List<String> notes = new ArrayList<>();
        }
    }
}
