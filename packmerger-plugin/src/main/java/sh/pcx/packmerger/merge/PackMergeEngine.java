package sh.pcx.packmerger.merge;

import com.google.gson.JsonObject;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.config.ConfigManager;
import sh.pcx.packmerger.merge.strategy.AtlasMergeStrategy;
import sh.pcx.packmerger.merge.strategy.BlockstateMergeStrategy;
import sh.pcx.packmerger.merge.strategy.EquipmentMergeStrategy;
import sh.pcx.packmerger.merge.strategy.FontMergeStrategy;
import sh.pcx.packmerger.merge.strategy.ItemDefinitionMergeStrategy;
import sh.pcx.packmerger.merge.strategy.LanguageMergeStrategy;
import sh.pcx.packmerger.merge.strategy.MergeContext;
import sh.pcx.packmerger.merge.strategy.MergeStrategy;
import sh.pcx.packmerger.merge.strategy.ModelMergeStrategy;
import sh.pcx.packmerger.merge.strategy.ModelPolicy;
import sh.pcx.packmerger.merge.strategy.PackMcmetaMergeStrategy;
import sh.pcx.packmerger.merge.strategy.SoundsMergeStrategy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import sh.pcx.packmerger.PluginLogger;

/**
 * Core merge engine that combines multiple Minecraft resource packs into a single output zip.
 *
 * <p>The merge strategy is priority-based: packs are processed from lowest to highest priority
 * so that higher-priority files overwrite lower-priority ones. This applies to non-JSON files
 * (textures, sounds, etc.) which use a simple last-write-wins approach.</p>
 *
 * <p>Certain JSON files receive format-specific merge treatment via registered
 * {@link MergeStrategy} implementations instead of being overwritten. Each strategy
 * encapsulates the correct semantics for its format — e.g. the model strategy concat-
 * dedups {@code overrides} arrays so that two packs adding CustomModelData to the
 * same vanilla item (the QualityArmory compatibility case) both survive. See the
 * {@code sh.pcx.packmerger.merge.strategy} package.</p>
 *
 * <p>The engine also supports custom overrides: placing a {@code pack.mcmeta} or
 * {@code pack.png} directly in the packs folder (not inside a pack) overrides the
 * merged pack's metadata and icon regardless of priority.</p>
 *
 * <p>Called from {@link PackMerger#mergeAndUpload} on an async thread. This class
 * does not interact with the Bukkit API and is safe to run off the main thread.</p>
 *
 * @see JsonMerger
 * @see PackValidator
 * @see PackMerger#mergeAndUpload(org.bukkit.command.CommandSender)
 */
public class PackMergeEngine {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMergerBootstrap plugin;

    /** Colored console logger. */
    private final PluginLogger logger;

    /**
     * JSON merge strategies checked in registration order — the first matching
     * strategy is used, so list more specific patterns before more general ones.
     */
    private final List<MergeStrategy> mergeStrategies;

    /**
     * Populated during the most recent {@link #merge()} call; {@code null} until
     * the first merge completes. Exposes which pack contributed each output file.
     */
    private MergeProvenance lastProvenance;

    /**
     * Filenames (lowercase) considered junk/OS metadata that should be stripped
     * from the merged pack when the strip-junk-files option is enabled.
     */
    private static final Set<String> JUNK_FILES = Set.of(
            ".ds_store", "thumbs.db", "desktop.ini", ".gitignore", ".gitattributes"
    );

    /**
     * Directory names (lowercase) considered junk that should be excluded
     * entirely from the merged pack (e.g. macOS resource forks, git metadata).
     */
    private static final Set<String> JUNK_DIRS = Set.of(
            "__macosx", ".git"
    );

    /**
     * Creates a new merge engine instance.
     *
     * @param plugin the owning PackMergerBootstrap plugin
     */
    public PackMergeEngine(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        // Read on every merge so /pm reload applies a changed merge.model-policy.
        Supplier<ModelPolicy> modelPolicy = () -> plugin.getConfigManager().getModelPolicy();
        // Every assets/ pattern also matches the same path inside an overlay directory.
        this.mergeStrategies = List.of(
                new PackMcmetaMergeStrategy(),    // pack.mcmeta — overlays.entries concat dedup by directory, format range repair
                new SoundsMergeStrategy(),        // sounds.json — checked before models/ to avoid shadowing
                new ModelMergeStrategy(modelPolicy),      // models/**.json — overrides dedup by predicate, sorted by CMD; rest per model-policy
                new BlockstateMergeStrategy(modelPolicy), // blockstates/**.json — multipart dedup by when; rest per model-policy
                new AtlasMergeStrategy(),         // atlases/**.json — sources concat (1.19.3+)
                new ItemDefinitionMergeStrategy(),// items/**.json    — range_dispatch/select union, else high wins whole (1.21.4+)
                new EquipmentMergeStrategy(),     // equipment/**.json — deep-merge (1.21.2+)
                new FontMergeStrategy(),          // font/**.json — providers concat
                new LanguageMergeStrategy()       // lang/**.json — translation-key union
        );
    }

    /**
     * Performs the full merge of all discovered resource packs and writes the output zip.
     *
     * <p>The merge process:</p>
     * <ol>
     *   <li>Discover available packs in the packs folder (zips and directories)</li>
     *   <li>Build a priority-ordered pack list, factoring in per-server configs</li>
     *   <li>Iterate packs from lowest to highest priority, merging files into memory</li>
     *   <li>Apply JSON deep merge results back into the file map</li>
     *   <li>Apply custom pack.mcmeta/pack.png overrides if present</li>
     *   <li>Generate a default pack.mcmeta if none exists</li>
     *   <li>Write the final zip to the output folder</li>
     * </ol>
     *
     * @return the output zip {@link File}, or {@code null} if no packs were found or the merge
     *         produced no files
     * @throws IOException if reading source packs or writing the output zip fails
     */
    /** @return the format-aware JSON strategies, in match order (shared with cluster staging) */
    public List<MergeStrategy> getMergeStrategies() {
        return mergeStrategies;
    }

    public File merge() throws IOException {
        return merge(null);
    }

    /**
     * Like {@link #merge()}, but writes the output zip to {@code targetOverride}
     * instead of {@link PackMerger#getOutputFile()}. Callers that want
     * rollback-on-validation-failure pass a temp file here, validate the result,
     * and then atomically rename to the real output if validation passed.
     *
     * <p>The provenance sidecar ({@code <target>.provenance.json}) is written
     * alongside {@code targetOverride} with the same name stem, so rollback
     * can delete both together.</p>
     *
     * @param targetOverride the target zip location, or {@code null} to use
     *                       {@link PackMerger#getOutputFile()} (legacy path)
     */
    public File merge(File targetOverride) throws IOException {
        File packsFolder = plugin.getPacksFolder();
        ConfigManager config = plugin.getConfigManager();

        // Step 1: Discover available packs (zips and directories with assets/ or pack.mcmeta)
        List<String> availablePacks = discoverPacks(packsFolder);
        if (availablePacks.isEmpty()) {
            logger.warning("No resource packs found in " + packsFolder.getAbsolutePath());
            return null;
        }

        logger.merge("Discovered " + availablePacks.size() + " pack(s): " + availablePacks);

        // Step 2: Build ordered pack list based on priority config and per-server settings
        List<String> orderedPacks = buildPackOrder(availablePacks, config);

        logger.merge("Merge order (highest priority first): " + orderedPacks);

        // Check for custom pack.mcmeta and pack.png overrides placed directly in the packs folder
        File customMcmeta = new File(packsFolder, "pack.mcmeta");
        File customIcon = new File(packsFolder, "pack.png");

        // Step 3: Merge all packs
        // mergedFiles holds non-JSON (or non-mergeable) file contents keyed by normalized path
        Map<String, byte[]> mergedFiles = new LinkedHashMap<>();
        // mergedJson holds parsed JSON objects for files that get deep-merged instead of overwritten
        Map<String, JsonObject> mergedJson = new LinkedHashMap<>();
        // Provenance tracker: records which pack contributed each path
        MergeProvenance.Builder provenance = new MergeProvenance.Builder();

        // Process packs in REVERSE order (lowest priority first) so that higher-priority
        // packs overwrite lower-priority ones via map.put() for non-JSON files, or take
        // precedence in JSON deep merge operations
        for (int i = orderedPacks.size() - 1; i >= 0; i--) {
            String packName = orderedPacks.get(i);
            File packFile = resolvePackFile(packsFolder, packName);

            if (!packFile.exists()) {
                logger.warning("Pack not found: " + packName + " (skipping)");
                continue;
            }

            try {
                int filesBefore = mergedFiles.size() + mergedJson.size();
                if (packFile.isDirectory()) {
                    mergeDirectory(packFile, packFile.toPath(), mergedFiles, mergedJson, config.isStripJunkFiles(), packName, provenance);
                } else if (packFile.getName().endsWith(".zip")) {
                    mergeZip(packFile, mergedFiles, mergedJson, config.isStripJunkFiles(), packName, provenance);
                }
                int filesAdded = (mergedFiles.size() + mergedJson.size()) - filesBefore;
                logger.merge("Merged pack: " + packName + " (" + filesAdded + " new files)");
            } catch (Exception e) {
                logger.warning("Failed to read pack: " + packName + " (skipping)", e);
            }
        }

        // Step 4: Serialize merged JSON objects back into byte arrays and add them to mergedFiles
        for (Map.Entry<String, JsonObject> entry : mergedJson.entrySet()) {
            mergedFiles.put(entry.getKey(), JsonMerger.toJson(entry.getValue()).getBytes(StandardCharsets.UTF_8));
        }

        // Step 5: Apply custom overrides — these take precedence over everything
        if (customMcmeta.exists() && customMcmeta.isFile()) {
            logger.merge("Using custom pack.mcmeta from packs folder");
            mergedFiles.put("pack.mcmeta", Files.readAllBytes(customMcmeta.toPath()));
            provenance.recordExternal("pack.mcmeta", "<custom:pack.mcmeta>");
        }

        if (customIcon.exists() && customIcon.isFile()) {
            logger.merge("Using custom pack.png from packs folder");
            mergedFiles.put("pack.png", Files.readAllBytes(customIcon.toPath()));
            provenance.recordExternal("pack.png", "<custom:pack.png>");
        }

        // Step 6: Ensure a pack.mcmeta exists — Minecraft requires it for valid resource packs
        if (!mergedFiles.containsKey("pack.mcmeta")) {
            logger.merge("No pack.mcmeta found, generating default");
            // pack_format 46 corresponds to Minecraft 1.21.4+
            String defaultMcmeta = """
                    {
                      "pack": {
                        "pack_format": 46,
                        "description": "Merged resource pack by PackMerger"
                      }
                    }""";
            mergedFiles.put("pack.mcmeta", defaultMcmeta.getBytes(StandardCharsets.UTF_8));
            provenance.recordExternal("pack.mcmeta", "<generated:default>");
        }

        if (mergedFiles.isEmpty()) {
            logger.warning("No files to merge after processing all packs");
            return null;
        }

        // Step 7: Write the output zip. Callers that want rollback-on-validation-failure
        // pass a temp target via {@link #merge(File)}; the default merge() writes
        // directly to plugin.getOutputFile() for backwards compatibility.
        File outputFile = targetOverride != null ? targetOverride : plugin.getOutputFile();
        outputFile.getParentFile().mkdirs();

        writeZip(outputFile, mergedFiles, config.getCompressionLevel());

        // Log the final file size for operator awareness
        long sizeBytes = outputFile.length();
        String sizeStr = formatSize(sizeBytes);
        logger.merge("Merged pack written: " + outputFile.getName() + " (" + sizeStr + ")");

        // Warn if the pack exceeds the configured size threshold
        int warningMb = config.getSizeWarningMb();
        if (warningMb > 0) {
            long sizeMb = sizeBytes / (1024 * 1024);
            if (sizeMb > warningMb) {
                logger.warning("Merged pack is " + sizeStr +
                        ", which exceeds the configured threshold of " + warningMb +
                        " MB. Large packs may cause download failures for players on slow connections.");
            }
        }

        // Freeze provenance and persist alongside the output zip so restarts
        // don't blank the state for /pm inspect and API consumers.
        this.lastProvenance = provenance.build(orderedPacks, Instant.now());
        int noted = this.lastProvenance.notedCount();
        if (noted > 0) {
            logger.merge(noted + " file(s) have merge notes (collisions resolved or metadata repaired); "
                    + "see /pm inspect collisions or the provenance sidecar");
        }
        writeProvenance(outputFile, this.lastProvenance);

        return outputFile;
    }

    /**
     * @return the provenance of the most recent successful merge, or {@code null}
     *         if no merge has completed yet during this plugin's lifetime
     */
    public MergeProvenance getLastProvenance() {
        return lastProvenance;
    }

    /**
     * Overwrites the in-memory provenance state, used on plugin startup to
     * restore from the on-disk {@code .merge-provenance.json} so the inspect
     * command has data before any merge runs.
     */
    public void setLastProvenance(MergeProvenance provenance) {
        this.lastProvenance = provenance;
    }

    /**
     * Writes provenance to {@code <outputFile>.provenance.json} (sidecar to the
     * output zip) so rollback-on-validation-failure can delete the zip and its
     * provenance as a pair. Failures are logged but never propagate — provenance
     * is diagnostic, not load-bearing.
     */
    private void writeProvenance(File outputFile, MergeProvenance provenance) {
        File provenanceFile = provenanceSidecar(outputFile);
        try {
            Files.writeString(provenanceFile.toPath(), provenance.toJson(), StandardCharsets.UTF_8);
            logger.debug("Wrote merge provenance: " + provenanceFile.getName());
        } catch (IOException e) {
            logger.warning("Failed to persist merge provenance (" + e.getMessage() + ")");
        }
    }

    /**
     * @return the canonical provenance sidecar path for a given merged pack zip
     */
    public static File provenanceSidecar(File zipFile) {
        return new File(zipFile.getParentFile(), zipFile.getName() + ".provenance.json");
    }

    /**
     * Discovers valid resource packs in the given folder.
     *
     * <p>A file is considered a pack if it is either a {@code .zip} file or a directory
     * containing {@code pack.mcmeta} or an {@code assets/} subdirectory. Custom override
     * files ({@code pack.mcmeta}, {@code pack.png}) and hidden files are excluded.</p>
     *
     * @param packsFolder the directory to scan
     * @return a list of pack filenames/directory names found
     */
    private List<String> discoverPacks(File packsFolder) {
        List<String> packs = new ArrayList<>();
        File[] files = packsFolder.listFiles();
        if (files == null) return packs;

        for (File file : files) {
            String name = file.getName();
            // Skip custom override files — these are applied after the merge
            if (name.equals("pack.mcmeta") || name.equals("pack.png")) continue;
            // Skip hidden/dot files (includes .remote-cache/ which is handled below)
            if (name.startsWith(".")) continue;

            if (file.isDirectory()) {
                // Validate the directory looks like a resource pack
                if (new File(file, "pack.mcmeta").exists() || new File(file, "assets").exists()) {
                    packs.add(name);
                }
            } else if (name.endsWith(".zip")) {
                packs.add(name);
            }
        }

        // Also enumerate remote-cache packs by alias (filename without .zip).
        // Admins reference these in priority: as just "<alias>".
        addStagedAliases(new File(packsFolder, ".remote-cache"), packs);
        // And plugin-sourced packs staged from other installed plugins
        // (Oraxen, Nexo, ItemsAdder, …), referenced by their adapter alias.
        addStagedAliases(new File(packsFolder, ".plugin-packs"), packs);
        return packs;
    }

    /** Adds every {@code <alias>.zip} in a staging dir to {@code packs} as "{@code <alias>}". */
    private void addStagedAliases(File stageDir, List<String> packs) {
        if (!stageDir.isDirectory()) return;
        File[] cached = stageDir.listFiles();
        if (cached == null) return;
        for (File f : cached) {
            String n = f.getName();
            if (!n.endsWith(".zip")) continue;
            packs.add(n.substring(0, n.length() - ".zip".length()));
        }
    }

    /**
     * Resolves a pack name (as referenced in priority lists) to its actual
     * file location. Local packs live directly in {@code packs/} and their
     * name is the filename; remote-cache packs live in
     * {@code packs/.remote-cache/&lt;alias&gt;.zip} and their name is the alias
     * without the extension.
     */
    private File resolvePackFile(File packsFolder, String packName) {
        File direct = new File(packsFolder, packName);
        if (direct.exists()) return direct;
        File remoteCached = new File(new File(packsFolder, ".remote-cache"), packName + ".zip");
        if (remoteCached.exists()) return remoteCached;
        File pluginStaged = new File(new File(packsFolder, ".plugin-packs"), packName + ".zip");
        if (pluginStaged.exists()) return pluginStaged;
        return direct;  // return the direct path so the "not found" warning still has a sensible message
    }

    /**
     * Builds the final ordered pack list based on the global priority configuration
     * and per-server include/exclude rules.
     *
     * <p>The ordering logic:</p>
     * <ol>
     *   <li>Packs listed in the priority config are added first, in config order,
     *       excluding any packs in the server's exclude list</li>
     *   <li>Packs found on disk but not in any config are appended at lowest priority
     *       with a console warning</li>
     *   <li>Server-specific additional packs are appended last (below global packs)</li>
     * </ol>
     *
     * @param available the list of pack names discovered on disk
     * @param config    the configuration manager for priority and server-pack settings
     * @return the final ordered list (first = highest priority)
     */
    private List<String> buildPackOrder(List<String> available, ConfigManager config) {
        List<String> priority = new ArrayList<>(config.getPriority());
        List<String> ordered = new ArrayList<>();

        // Resolve per-server include/exclude rules
        ConfigManager.ServerPackConfig serverConfig = config.getServerPackConfig();
        Set<String> excluded = new HashSet<>();
        List<String> additional = new ArrayList<>();

        if (serverConfig != null) {
            excluded.addAll(serverConfig.exclude());
            additional.addAll(serverConfig.additional());
        }

        // First pass: add packs from the priority list in order, respecting server exclusions
        for (String pack : priority) {
            if (available.contains(pack) && !excluded.contains(pack)) {
                ordered.add(pack);
            }
        }

        // Second pass: add packs that exist on disk but aren't in any config list
        // These are sorted by filename (so numeric prefixes like 01_, 02_ are respected)
        List<String> unlisted = new ArrayList<>();
        for (String pack : available) {
            if (!priority.contains(pack) && !additional.contains(pack) && !excluded.contains(pack)) {
                unlisted.add(pack);
            }
        }
        Collections.sort(unlisted);
        ordered.addAll(unlisted);

        // Third pass: add server-specific additional packs at lowest priority (below global)
        for (String pack : additional) {
            if (available.contains(pack) && !ordered.contains(pack)) {
                ordered.add(pack);
            }
        }

        return ordered;
    }

    /**
     * Merges all files from a zip archive into the merge maps.
     *
     * @param zipFile    the zip file to read
     * @param mergedFiles map of normalized path &rarr; file bytes for non-mergeable files
     * @param mergedJson  map of normalized path &rarr; parsed JSON for deep-mergeable files
     * @param stripJunk   whether to skip junk/hidden files
     * @throws IOException if reading the zip fails
     */
    private void mergeZip(File zipFile, Map<String, byte[]> mergedFiles, Map<String, JsonObject> mergedJson, boolean stripJunk,
                          String packName, MergeProvenance.Builder provenance) throws IOException {
        try (ZipFile zf = new ZipFile(zipFile)) {
            // Detect if all entries share a common root folder prefix (e.g. "MyPack/assets/...")
            // Many third-party packs are zipped with a wrapper folder that needs stripping
            String rootPrefix = detectRootPrefix(zf);
            if (rootPrefix != null) {
                logger.debug("Stripping root folder prefix '" + rootPrefix + "' from " + zipFile.getName());
            }

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String path = normalizePath(entry.getName());
                if (path.isEmpty()) continue;

                // Strip the root folder prefix if one was detected
                if (rootPrefix != null) {
                    if (path.startsWith(rootPrefix)) {
                        path = path.substring(rootPrefix.length());
                    }
                    if (path.isEmpty()) continue;
                }

                // Optionally strip OS metadata and hidden files
                if (stripJunk && isJunkFile(path)) {
                    logger.debug("Stripping junk: " + path);
                    continue;
                }

                byte[] data;
                try (InputStream is = zf.getInputStream(entry)) {
                    data = is.readAllBytes();
                }

                processFile(path, data, mergedFiles, mergedJson, packName, provenance);
            }
        }
    }

    /**
     * Detects whether all entries in a zip share a common root folder prefix.
     *
     * <p>Many resource packs are zipped with a wrapper directory (e.g. the zip contains
     * {@code MyPack/assets/...} instead of {@code assets/...}). This method detects that
     * pattern by checking if any entry starts with {@code assets/} or {@code pack.mcmeta}
     * at the root level. If not, it looks for a single common top-level directory that
     * contains those files and returns it as the prefix to strip.</p>
     *
     * @param zf the zip file to inspect
     * @return the root prefix to strip (e.g. {@code "MyPack/"}), or {@code null} if no stripping is needed
     */
    private String detectRootPrefix(ZipFile zf) {
        boolean hasRootAssets = false;
        boolean hasRootMcmeta = false;
        Set<String> topLevelDirs = new HashSet<>();

        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = normalizePath(entry.getName());
            if (name.isEmpty()) continue;

            // Check if assets/ or pack.mcmeta exist at the root level
            if (name.startsWith("assets/") || name.equals("assets")) {
                hasRootAssets = true;
            }
            if (name.equals("pack.mcmeta")) {
                hasRootMcmeta = true;
            }

            // Track top-level directories
            int slash = name.indexOf('/');
            if (slash > 0) {
                topLevelDirs.add(name.substring(0, slash));
            }
        }

        // If assets/ or pack.mcmeta exist at root, no stripping needed
        if (hasRootAssets || hasRootMcmeta) {
            return null;
        }

        // If there's exactly one top-level directory, check if it contains assets/ or pack.mcmeta
        if (topLevelDirs.size() == 1) {
            String prefix = topLevelDirs.iterator().next() + "/";
            entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = normalizePath(entry.getName());
                if (name.startsWith(prefix + "assets/") || name.equals(prefix + "pack.mcmeta")) {
                    return prefix;
                }
            }
        }

        return null;
    }

    /**
     * Merges all files from an unzipped pack directory into the merge maps.
     *
     * @param baseDir     the root directory of the pack
     * @param basePath    the root path used to compute relative paths
     * @param mergedFiles map of normalized path &rarr; file bytes for non-mergeable files
     * @param mergedJson  map of normalized path &rarr; parsed JSON for deep-mergeable files
     * @param stripJunk   whether to skip junk/hidden files
     * @throws IOException if walking the directory tree fails
     */
    private void mergeDirectory(File baseDir, Path basePath, Map<String, byte[]> mergedFiles, Map<String, JsonObject> mergedJson, boolean stripJunk,
                                String packName, MergeProvenance.Builder provenance) throws IOException {
        try (var stream = Files.walk(basePath)) {
            stream.filter(Files::isRegularFile).forEach(filePath -> {
                try {
                    String relativePath = normalizePath(basePath.relativize(filePath).toString());
                    if (relativePath.isEmpty()) return;

                    if (stripJunk && isJunkFile(relativePath)) {
                        logger.debug("Stripping junk: " + relativePath);
                        return;
                    }

                    byte[] data = Files.readAllBytes(filePath);
                    processFile(relativePath, data, mergedFiles, mergedJson, packName, provenance);
                } catch (IOException e) {
                    logger.warning("Failed to read file: " + filePath, e);
                }
            });
        }
    }

    /**
     * Routes a single file into the appropriate merge map based on its path.
     *
     * <p>If any registered {@link MergeStrategy} matches the path, the file is parsed
     * as JSON and handled by that strategy. Otherwise it uses simple overwrite
     * semantics — the higher-priority pack's version wins.</p>
     *
     * @param path        the normalized file path within the resource pack
     * @param data        the raw file content bytes
     * @param mergedFiles map for non-mergeable files (overwrite semantics)
     * @param mergedJson  map for deep-mergeable JSON files
     */
    private void processFile(String path, byte[] data, Map<String, byte[]> mergedFiles, Map<String, JsonObject> mergedJson,
                             String packName, MergeProvenance.Builder provenance) {
        MergeStrategy strategy = findStrategy(path);
        if (strategy == null) {
            // Non-JSON or non-mergeable file: higher priority replaces lower priority (last write wins)
            mergedFiles.put(path, data);
            provenance.record(path, packName, null, false);
            return;
        }

        String content = new String(data, StandardCharsets.UTF_8);
        JsonObject newJson = JsonMerger.parseJson(content);
        if (newJson == null) {
            // Invalid JSON in a mergeable path — fall back to raw overwrite with a warning
            logger.warning("Invalid JSON in mergeable file (using raw): " + path);
            mergedFiles.put(path, data);
            provenance.record(path, packName, null, false);
            return;
        }

        JsonObject existing = mergedJson.get(path);
        if (existing != null) {
            // Already saw this file from a lower-priority pack — apply strategy merge.
            // Warnings and notes go to the console and into the provenance record,
            // so /pm inspect shows which packs collided and what was rewritten.
            MergeContext ctx = new MergeContext(path, packName, provenance.winnerOf(path),
                    msg -> {
                        logger.warning(msg);
                        provenance.note(path, msg);
                    },
                    msg -> {
                        logger.debug("Merge note for " + path + ": " + msg);
                        provenance.note(path, msg);
                    });
            mergedJson.put(path, strategy.merge(newJson, existing, ctx));
            provenance.record(path, packName, strategy.name(), true);
            logger.debug("JSON merged (" + strategy.name() + "): " + path);
        } else {
            // First occurrence — store as-is
            mergedJson.put(path, newJson);
            provenance.record(path, packName, strategy.name(), false);
        }
    }

    /**
     * Finds the first registered strategy that matches the given path, or {@code null}
     * if no strategy applies (in which case the file uses simple overwrite semantics).
     */
    private MergeStrategy findStrategy(String path) {
        for (MergeStrategy s : mergeStrategies) {
            if (s.matches(path)) return s;
        }
        return null;
    }

    /**
     * Determines whether a file should be treated as junk and stripped from the output.
     *
     * <p>Junk includes hidden/dot files, OS metadata files (.DS_Store, Thumbs.db,
     * desktop.ini), version control files (.gitignore, .gitattributes), and files
     * inside junk directories (__MACOSX, .git).</p>
     *
     * @param path the normalized file path to check
     * @return {@code true} if the file is junk
     */
    private boolean isJunkFile(String path) {
        String lower = path.toLowerCase().replace('\\', '/');
        String fileName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;

        // Hidden/dot files (e.g. .gitkeep, .hidden)
        if (fileName.startsWith(".")) return true;

        // Well-known OS metadata and VCS files
        if (JUNK_FILES.contains(fileName)) return true;

        // Check if any directory component in the path is a known junk directory
        String[] parts = lower.split("/");
        for (String part : parts) {
            if (JUNK_DIRS.contains(part)) return true;
        }

        return false;
    }

    /**
     * Normalizes a file path by converting backslashes to forward slashes and
     * stripping leading slashes. This ensures consistent path keys across
     * zip entries and directory walks regardless of OS.
     *
     * @param path the raw path string
     * @return the normalized path
     */
    private String normalizePath(String path) {
        return path.replace('\\', '/').replaceAll("^/+", "");
    }

    /**
     * Writes a map of file paths and their contents to a zip archive. The output is
     * deterministic (see {@link DeterministicZip}).
     *
     * @param outputFile       the zip file to create
     * @param files            map of path &rarr; content bytes
     * @param compressionLevel the ZIP compression level (0-9)
     * @throws IOException if writing fails
     */
    private void writeZip(File outputFile, Map<String, byte[]> files, int compressionLevel) throws IOException {
        // Fixed timestamps + sorted entries: identical content gives an identical SHA-1.
        DeterministicZip.write(outputFile, files, compressionLevel);
    }

    /**
     * Formats a byte count as a human-readable size string (B, KB, MB, or GB).
     *
     * @param bytes the size in bytes
     * @return a formatted string like "1.5 MB"
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
