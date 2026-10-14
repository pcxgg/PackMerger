package sh.pcx.packmerger.discovery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sh.pcx.packmerger.merge.strategy.MergeContext;
import sh.pcx.packmerger.merge.strategy.MergeStrategy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Stages plugin-generated packs into {@code packs/.plugin-packs/<alias>.zip}.
 * Holds no Bukkit references so every rule here is unit-tested with temp dirs;
 * {@link PluginPackManager} feeds it plugin presence and config.
 *
 * <p>One {@link #run} pass:</p>
 * <ol>
 *   <li>resolves each adapter's sources and fingerprints them (size + mtime, and
 *       for folders also the file list);</li>
 *   <li>skips sources whose fingerprint matches what is already staged;</li>
 *   <li>for changed sources, re-checks the fingerprint {@code polls - 1} more times,
 *       {@code intervalMillis} apart. A source that is still changing (a plugin
 *       halfway through writing its zip) is <em>deferred</em>: the previous staged
 *       copy, if any, is kept and the source is re-checked later;</li>
 *   <li>validates and stages: a single pack must have {@code pack.mcmeta} or
 *       {@code assets/} at its root (a zip wrapped in one top-level folder is
 *       unwrapped); a cluster folder is unioned from every valid pack inside it.
 *       A source that fails validation is not staged and is reported;</li>
 *   <li>deletes every staged file whose alias was not kept this pass (adapter
 *       disabled, plugin removed, source gone or invalid).</li>
 * </ol>
 */
public final class PluginPackStager {

    /** Sleeps between stability polls. Replaced in tests. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Downloads a URL adapter's pack into {@code stageDir/<alias>.zip}. */
    public interface UrlFetcher {
        /**
         * @return whether {@code <alias>.zip} is now usable (fresh download, 304, or
         *         a kept cached copy after a failed fetch) plus a detail line
         */
        FetchOutcome fetch(PluginPackAdapter adapter, File stageDir);
    }

    /** Result of a {@link UrlFetcher} call. */
    public record FetchOutcome(boolean usable, boolean downloaded, String detail) {}

    /** What happened to one adapter during a pass. */
    public enum Status {
        /** Freshly copied/built into the stage dir. */
        STAGED,
        /** Source unchanged since the last pass; the staged copy is reused. */
        UNCHANGED,
        /** Source still changing; staging postponed (previous copy kept if present). */
        DEFERRED,
        /** URL adapter: downloaded now or served from the cached download. */
        FETCHED,
        /** {@code enabled: false} in config. */
        DISABLED,
        /** The plugin is not installed (silent: not an advisory). */
        NOT_INSTALLED,
        /** Plugin installed but no source exists yet. */
        NOT_FOUND,
        /** Source exists but is not a resource pack (no pack.mcmeta/assets at its root). */
        INVALID,
        /** I/O or download failure. */
        FAILED
    }

    /** Outcome of one adapter. */
    public record Outcome(String alias, String pluginName, Status status, String detail) {
        /** @return {@code true} when the pack takes part in the merge */
        public boolean merged() {
            return status == Status.STAGED || status == Status.UNCHANGED || status == Status.FETCHED;
        }

        /** @return {@code true} when ops should be told the plugin's pack is not merged */
        public boolean advisory() {
            return status == Status.DISABLED || status == Status.NOT_FOUND
                    || status == Status.INVALID || status == Status.FAILED;
        }
    }

    /** All outcomes of a pass plus the aliases whose stale staged files were deleted. */
    public record Report(List<Outcome> outcomes, List<String> pruned) {
        public List<String> mergedAliases() {
            return outcomes.stream().filter(Outcome::merged).map(Outcome::alias).toList();
        }

        public boolean hasDeferred() {
            return outcomes.stream().anyMatch(o -> o.status() == Status.DEFERRED);
        }

        public int stagedCount() {
            return (int) outcomes.stream().filter(o -> o.status() == Status.STAGED).count();
        }

        public Outcome outcome(String alias) {
            return outcomes.stream().filter(o -> o.alias().equals(alias)).findFirst().orElse(null);
        }
    }

    /** One adapter plus the facts {@link PluginPackManager} knows about it. */
    public record Entry(PluginPackAdapter adapter, boolean enabled, boolean installed) {}

    /** Thrown when a source is not a usable resource pack. */
    static final class InvalidSourceException extends Exception {
        InvalidSourceException(String message) { super(message); }
    }

    static final String ABSENT = "absent";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final File pluginsFolder;
    private final File stageDir;
    private final List<MergeStrategy> strategies;
    private final int polls;
    private final long intervalMillis;
    private final Sleeper sleeper;
    private final UrlFetcher fetcher;
    private final Consumer<String> warnings;

    /** Fingerprint of the sources behind each currently staged zip. */
    private final Map<String, String> stagedFingerprints = new ConcurrentHashMap<>();
    /** Fingerprint each alias had when a pass last looked at it (staged or not). */
    private final Map<String, String> processedFingerprints = new ConcurrentHashMap<>();
    /** Watcher state: fingerprint seen on the previous poll, awaiting confirmation. */
    private final Map<String, String> pendingFingerprints = new ConcurrentHashMap<>();

    /**
     * @param pluginsFolder  the server's {@code plugins/} directory
     * @param stageDir       {@code packs/.plugin-packs/}
     * @param strategies     JSON merge strategies used when cluster members collide
     * @param polls          how many identical fingerprints are needed before copying (≥1)
     * @param intervalMillis delay between those polls
     * @param sleeper        sleep implementation ({@code Thread::sleep} in production)
     * @param fetcher        downloader for URL adapters ({@code null}: URL adapters fail)
     * @param warnings       sink for non-fatal notes (cluster members skipped, collisions)
     */
    public PluginPackStager(File pluginsFolder, File stageDir, List<MergeStrategy> strategies,
                            int polls, long intervalMillis, Sleeper sleeper, UrlFetcher fetcher,
                            Consumer<String> warnings) {
        this.pluginsFolder = pluginsFolder;
        this.stageDir = stageDir;
        this.strategies = strategies == null ? List.of() : List.copyOf(strategies);
        this.polls = Math.max(1, polls);
        this.intervalMillis = Math.max(0, intervalMillis);
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
        this.fetcher = fetcher;
        this.warnings = warnings == null ? s -> { } : warnings;
    }

    /** @return the staged zip for an alias */
    public File stagedFile(String alias) {
        return new File(stageDir, alias + ".zip");
    }

    // -------------------------------------------------------------------------
    // The staging pass
    // -------------------------------------------------------------------------

    /**
     * Runs one staging pass.
     *
     * @param entries       every known adapter with its enabled/installed state
     * @param refreshRemote re-download URL adapters even when a cached copy exists
     */
    public Report run(List<Entry> entries, boolean refreshRemote) {
        stageDir.mkdirs();
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> keep = new HashSet<>();

        record Pending(Entry entry, List<PluginPackAdapter.ResolvedSource> sources, String fingerprint) {}
        List<Pending> changed = new ArrayList<>();

        for (Entry e : entries) {
            PluginPackAdapter a = e.adapter();
            String alias = a.alias();
            if (!e.installed()) {
                outcomes.add(new Outcome(alias, a.pluginName(), Status.NOT_INSTALLED, null));
                continue;
            }
            if (!e.enabled()) {
                outcomes.add(new Outcome(alias, a.pluginName(), Status.DISABLED, "disabled in config"));
                continue;
            }
            if (a.isRemote()) {
                Outcome o = stageRemote(a, refreshRemote);
                if (o.merged()) keep.add(alias);
                outcomes.add(o);
                continue;
            }
            List<PluginPackAdapter.ResolvedSource> sources = a.resolve(pluginsFolder);
            if (sources.isEmpty()) {
                processedFingerprints.put(alias, ABSENT);
                outcomes.add(new Outcome(alias, a.pluginName(), Status.NOT_FOUND,
                        "no generated pack at " + String.join(" or ", a.candidatePaths())));
                continue;
            }
            String fp = fingerprint(sources);
            if (fp.equals(stagedFingerprints.get(alias)) && stagedFile(alias).isFile()) {
                processedFingerprints.put(alias, fp);
                keep.add(alias);
                outcomes.add(new Outcome(alias, a.pluginName(), Status.UNCHANGED, describe(sources)));
                continue;
            }
            changed.add(new Pending(e, sources, fp));
        }

        // Stability window: only changed sources are polled, so a steady-state merge
        // never sleeps.
        Set<String> unstable = new HashSet<>();
        if (!changed.isEmpty() && polls > 1) {
            for (int i = 1; i < polls && unstable.size() < changed.size(); i++) {
                try {
                    sleeper.sleep(intervalMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                for (Pending p : changed) {
                    String alias = p.entry().adapter().alias();
                    if (unstable.contains(alias)) continue;
                    List<PluginPackAdapter.ResolvedSource> now = p.entry().adapter().resolve(pluginsFolder);
                    if (now.isEmpty() || !fingerprint(now).equals(p.fingerprint())) unstable.add(alias);
                }
            }
        }

        for (Pending p : changed) {
            PluginPackAdapter a = p.entry().adapter();
            String alias = a.alias();
            if (unstable.contains(alias)) {
                // Record the pre-poll fingerprint: the source has moved on since, so the
                // watcher sees a difference and re-merges once it settles.
                processedFingerprints.put(alias, p.fingerprint());
                boolean hasPrevious = stagedFile(alias).isFile();
                if (hasPrevious) keep.add(alias);
                outcomes.add(new Outcome(alias, a.pluginName(), Status.DEFERRED,
                        "source is still being written; " + (hasPrevious
                                ? "keeping the previously staged copy" : "will retry once it settles")));
                continue;
            }
            processedFingerprints.put(alias, p.fingerprint());
            try {
                String detail = stage(alias, p.sources());
                stagedFingerprints.put(alias, p.fingerprint());
                keep.add(alias);
                outcomes.add(new Outcome(alias, a.pluginName(), Status.STAGED, detail));
            } catch (InvalidSourceException ise) {
                outcomes.add(new Outcome(alias, a.pluginName(), Status.INVALID, ise.getMessage()));
            } catch (IOException | UncheckedIOException ioe) {
                outcomes.add(new Outcome(alias, a.pluginName(), Status.FAILED,
                        "staging failed: " + ioe.getMessage()));
            }
        }

        List<String> pruned = prune(stageDir, keep);
        for (String alias : pruned) stagedFingerprints.remove(alias);
        // Forget fingerprints for aliases that are no longer active so a later
        // re-enable stages afresh.
        for (Outcome o : outcomes) {
            if (o.status() == Status.DISABLED || o.status() == Status.NOT_INSTALLED) {
                stagedFingerprints.remove(o.alias());
                processedFingerprints.remove(o.alias());
                pendingFingerprints.remove(o.alias());
            }
        }
        return new Report(outcomes, pruned);
    }

    /**
     * Deletes everything the stager owns: used when {@code plugin-packs.enabled}
     * is turned off so no stale copy keeps merging.
     */
    public List<String> clear() {
        stagedFingerprints.clear();
        processedFingerprints.clear();
        pendingFingerprints.clear();
        return prune(stageDir, Set.of());
    }

    private Outcome stageRemote(PluginPackAdapter a, boolean refresh) {
        String alias = a.alias();
        File staged = stagedFile(alias);
        String detail = "cached download";
        if (refresh || !staged.isFile()) {
            if (fetcher == null) {
                return new Outcome(alias, a.pluginName(), Status.FAILED, "downloads are not available");
            }
            FetchOutcome fo = fetcher.fetch(a, stageDir);
            if (!fo.usable() || !staged.isFile()) {
                return new Outcome(alias, a.pluginName(), Status.FAILED,
                        "download failed: " + fo.detail());
            }
            detail = fo.detail();
        }
        try {
            if (zipRoot(staged) == null) {
                return new Outcome(alias, a.pluginName(), Status.INVALID,
                        "downloaded file has no pack.mcmeta or assets/ at its root");
            }
        } catch (InvalidSourceException ise) {
            return new Outcome(alias, a.pluginName(), Status.INVALID, ise.getMessage());
        }
        return new Outcome(alias, a.pluginName(), Status.FETCHED, detail);
    }

    // -------------------------------------------------------------------------
    // Watcher support
    // -------------------------------------------------------------------------

    /**
     * One watcher poll. Compares each active local adapter's current fingerprint to
     * the one the last staging pass saw. A difference must be observed on two
     * consecutive polls (debounce) before this returns {@code true}.
     *
     * <p>Only aliases a staging pass has already looked at are considered, so the
     * watcher never races the delayed first merge.</p>
     *
     * @param active adapters that are installed and enabled
     * @return {@code true} when a settled change exists and a merge should run
     */
    public boolean pollForChanges(Collection<PluginPackAdapter> active) {
        boolean trigger = false;
        Set<String> seen = new HashSet<>();
        for (PluginPackAdapter a : active) {
            if (a.isRemote()) continue;
            String alias = a.alias();
            seen.add(alias);
            String processed = processedFingerprints.get(alias);
            if (processed == null) continue;
            List<PluginPackAdapter.ResolvedSource> sources = a.resolve(pluginsFolder);
            String now = sources.isEmpty() ? ABSENT : fingerprint(sources);
            if (now.equals(processed)) {
                pendingFingerprints.remove(alias);
            } else if (now.equals(pendingFingerprints.get(alias))) {
                trigger = true;
            } else {
                pendingFingerprints.put(alias, now);
            }
        }
        pendingFingerprints.keySet().retainAll(seen);
        if (trigger) pendingFingerprints.clear();
        return trigger;
    }

    // -------------------------------------------------------------------------
    // Building a staged zip
    // -------------------------------------------------------------------------

    /** A single pack found inside a source: a folder, or a zip with an optional root prefix. */
    record Member(File file, String zipPrefix) {
        boolean isZip() { return zipPrefix != null; }
    }

    /**
     * Builds {@code <alias>.zip} from the resolved sources via a {@code .part} file
     * and an atomic rename.
     *
     * @return a short description for the log
     */
    String stage(String alias, List<PluginPackAdapter.ResolvedSource> sources)
            throws IOException, InvalidSourceException {
        List<Member> members = new ArrayList<>();
        for (PluginPackAdapter.ResolvedSource s : sources) members.addAll(expand(s));

        File part = new File(stageDir, alias + ".zip.part");
        File dest = stagedFile(alias);
        try {
            if (members.size() == 1 && members.get(0).isZip() && members.get(0).zipPrefix().isEmpty()) {
                // Fast path: a single well-formed zip is copied byte for byte.
                Files.copy(members.get(0).file().toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                union(members, part, alias);
            }
            move(part, dest);
        } finally {
            Files.deleteIfExists(part.toPath());
        }
        return describe(sources) + (members.size() > 1 ? " (" + members.size() + " packs unioned)" : "");
    }

    /** Expands one resolved source into the packs it contains, validating each. */
    List<Member> expand(PluginPackAdapter.ResolvedSource s) throws InvalidSourceException {
        File f = s.file();
        if (f.isFile()) {
            String prefix = zipRoot(f);
            if (prefix == null) {
                throw new InvalidSourceException(f.getName()
                        + " has no pack.mcmeta or assets/ at its root (not a resource pack)");
            }
            return List.of(new Member(f, prefix));
        }
        if (!s.cluster() && isPackDir(f)) return List.of(new Member(f, null));

        List<Member> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        File[] children = f.listFiles();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) {
                if (child.getName().startsWith(".")) continue;
                if (child.isDirectory()) {
                    if (isPackDir(child)) out.add(new Member(child, null));
                    else skipped.add(child.getName());
                } else if (child.getName().toLowerCase().endsWith(".zip")) {
                    String prefix;
                    try {
                        prefix = zipRoot(child);
                    } catch (InvalidSourceException ise) {
                        prefix = null;
                    }
                    if (prefix != null) out.add(new Member(child, prefix));
                    else skipped.add(child.getName());
                }
            }
        }
        if (!skipped.isEmpty() && !out.isEmpty()) {
            warnings.accept("cluster " + f.getName() + ": skipped " + skipped
                    + " (no pack.mcmeta or assets/ at their root)");
        }
        if (out.isEmpty()) {
            throw new InvalidSourceException(s.cluster()
                    ? f.getName() + "/ is a cluster folder but contains no resource packs"
                    : f.getName() + "/ has no pack.mcmeta or assets/ at its root and contains no packs");
        }
        return out;
    }

    /**
     * Writes the union of {@code members} into {@code dest}. Earlier members win
     * plain-file collisions; JSON files that a merge strategy understands
     * (lang, atlases, sounds, models, …) are merged format-aware instead, the
     * earlier member taking precedence on conflicts.
     */
    private void union(List<Member> members, File dest, String alias) throws IOException {
        Set<String> written = new HashSet<>();
        Map<String, JsonObject> mergedJson = new LinkedHashMap<>();
        Set<String> collisions = new TreeSet<>();

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(dest.toPath()))) {
            for (Member m : members) {
                forEachEntry(m, (name, in) -> {
                    MergeStrategy strategy = name.endsWith(".json") || name.equals("pack.mcmeta") ? strategyFor(name) : null;
                    if (strategy != null) {
                        byte[] raw = in.readAllBytes();
                        JsonObject incoming = parseObject(raw);
                        if (incoming == null) {
                            // Not mergeable: keep it verbatim if it is the first copy.
                            if (!mergedJson.containsKey(name) && written.add(name)) {
                                zos.putNextEntry(new ZipEntry(name));
                                zos.write(raw);
                                zos.closeEntry();
                            } else {
                                collisions.add(name);
                            }
                            return;
                        }
                        if (written.contains(name)) {
                            collisions.add(name);
                            return;
                        }
                        JsonObject existing = mergedJson.get(name);
                        if (existing == null) {
                            mergedJson.put(name, incoming);
                        } else {
                            mergedJson.put(name, strategy.merge(existing, incoming,
                                    new MergeContext(name, warnings)));
                        }
                        return;
                    }
                    if (!written.add(name)) {
                        collisions.add(name);
                        return;
                    }
                    zos.putNextEntry(new ZipEntry(name));
                    in.transferTo(zos);
                    zos.closeEntry();
                });
            }
            for (Map.Entry<String, JsonObject> e : mergedJson.entrySet()) {
                if (!written.add(e.getKey())) continue;
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(GSON.toJson(e.getValue()).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        if (!collisions.isEmpty()) {
            warnings.accept(alias + ": " + collisions.size() + " file(s) exist in more than one pack of the "
                    + "cluster; the alphabetically first pack wins: " + abbreviate(collisions));
        }
    }

    private interface EntryVisitor {
        void visit(String name, InputStream in) throws IOException;
    }

    /** Visits every file of a member with its pack-relative, forward-slash name. */
    private static void forEachEntry(Member m, EntryVisitor visitor) throws IOException {
        if (m.isZip()) {
            try (ZipFile zf = new ZipFile(m.file())) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    String name = ze.getName().replace('\\', '/');
                    if (!name.startsWith(m.zipPrefix())) continue;
                    name = name.substring(m.zipPrefix().length());
                    if (!safeName(name)) continue;
                    try (InputStream in = zf.getInputStream(ze)) {
                        visitor.visit(name, in);
                    }
                }
            }
            return;
        }
        Path base = m.file().toPath();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(base)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path p : files) {
            String name = base.relativize(p).toString().replace(File.separatorChar, '/');
            if (!safeName(name)) continue;
            try (InputStream in = Files.newInputStream(p)) {
                visitor.visit(name, in);
            }
        }
    }

    private MergeStrategy strategyFor(String path) {
        for (MergeStrategy s : strategies) if (s.matches(path)) return s;
        return null;
    }

    private static JsonObject parseObject(byte[] bytes) {
        try {
            JsonElement el = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean safeName(String name) {
        return !name.isEmpty() && !name.startsWith("/") && !name.contains("../") && !name.equals("..");
    }

    private static String abbreviate(Collection<String> items) {
        List<String> l = new ArrayList<>(items);
        return l.size() <= 5 ? String.join(", ", l) : String.join(", ", l.subList(0, 5)) + ", …";
    }

    private static void move(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String describe(List<PluginPackAdapter.ResolvedSource> sources) {
        List<String> parts = new ArrayList<>();
        for (PluginPackAdapter.ResolvedSource s : sources) {
            parts.add(relative(s.file()) + (s.cluster() ? " [cluster]" : ""));
        }
        return String.join(" + ", parts);
    }

    private String relative(File f) {
        try {
            return pluginsFolder.toPath().toAbsolutePath().normalize()
                    .relativize(f.toPath().toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return f.getPath();
        }
    }

    // -------------------------------------------------------------------------
    // Validation, fingerprints and pruning (static, shared with RemotePackManager)
    // -------------------------------------------------------------------------

    /** @return {@code true} if {@code dir} has {@code pack.mcmeta} or {@code assets/} at its root */
    public static boolean isPackDir(File dir) {
        return dir.isDirectory()
                && (new File(dir, "pack.mcmeta").isFile() || new File(dir, "assets").isDirectory());
    }

    /**
     * Finds where the pack starts inside a zip.
     *
     * @return {@code ""} when {@code pack.mcmeta} or {@code assets/} is at the root,
     *         {@code "<folder>/"} when the whole pack is wrapped in one top-level
     *         folder, or {@code null} when the zip is not a resource pack
     * @throws InvalidSourceException if the zip cannot be read (corrupt, still
     *                                being written, or protected against unzipping)
     */
    static String zipRoot(File zip) throws InvalidSourceException {
        Set<String> tops = new HashSet<>();
        boolean rootPack = false;
        Set<String> wrappedPacks = new HashSet<>();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().replace('\\', '/');
                if (n.equals("pack.mcmeta") || n.startsWith("assets/")) rootPack = true;
                int slash = n.indexOf('/');
                String top = slash < 0 ? n : n.substring(0, slash);
                if (top.equals("__MACOSX")) continue;
                tops.add(top);
                if (slash > 0) {
                    String rest = n.substring(slash + 1);
                    if (rest.equals("pack.mcmeta") || rest.startsWith("assets/")) wrappedPacks.add(top);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new InvalidSourceException(zip.getName() + " cannot be read as a zip (" + e.getMessage()
                    + "); it may be corrupt, still being written, or protected against unzipping");
        }
        if (rootPack) return "";
        if (tops.size() == 1 && wrappedPacks.size() == 1) return wrappedPacks.iterator().next() + "/";
        return null;
    }

    /**
     * A cheap change fingerprint: size and mtime for a file; file count, total
     * size, newest mtime and a hash of the relative names for a folder.
     */
    static String fingerprint(List<PluginPackAdapter.ResolvedSource> sources) {
        StringBuilder sb = new StringBuilder();
        for (PluginPackAdapter.ResolvedSource s : sources) {
            if (!sb.isEmpty()) sb.append('|');
            sb.append(s.file().getAbsolutePath()).append(s.cluster() ? "#c:" : ":").append(fingerprint(s.file()));
        }
        return sb.toString();
    }

    static String fingerprint(File f) {
        if (f.isFile()) return "f" + f.length() + "@" + f.lastModified();
        if (!f.isDirectory()) return ABSENT;
        long count = 0, total = 0, newest = 0, names = 17;
        Path base = f.toPath();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                File file = p.toFile();
                long mtime = file.lastModified();
                newest = Math.max(newest, mtime);
                names = names * 31 + base.relativize(p).toString().hashCode();
                if (file.isFile()) {
                    count++;
                    total += file.length();
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // A file vanished mid-walk: the folder is changing, so return a unique value.
            return "d-unreadable@" + System.nanoTime();
        }
        return "d" + count + ":" + total + "@" + newest + "#" + Long.toHexString(names);
    }

    /**
     * Deletes {@code <alias>.zip}, {@code <alias>.zip.part} and {@code <alias>.meta.json}
     * in {@code dir} for every alias not in {@code keep}. Leftover {@code .part}
     * files older than a minute are removed too.
     *
     * @return the aliases whose staged zip was deleted, sorted
     */
    public static List<String> prune(File dir, Set<String> keep) {
        File[] files = dir.listFiles();
        if (files == null) return List.of();
        Set<String> deleted = new TreeSet<>();
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName();
            String alias;
            boolean zip = false;
            if (n.endsWith(".zip.part")) {
                // Temp file from an interrupted pass. A fresh one may belong to a
                // download running right now, so only old ones are removed.
                if (System.currentTimeMillis() - f.lastModified() < 60_000) continue;
                alias = null;
            } else if (n.endsWith(".zip")) {
                alias = n.substring(0, n.length() - 4);
                zip = true;
            } else if (n.endsWith(".meta.json")) {
                alias = n.substring(0, n.length() - ".meta.json".length());
            } else {
                continue;
            }
            if (alias != null && keep.contains(alias)) continue;
            if (f.delete() && zip) deleted.add(alias);
        }
        return new ArrayList<>(deleted);
    }
}
