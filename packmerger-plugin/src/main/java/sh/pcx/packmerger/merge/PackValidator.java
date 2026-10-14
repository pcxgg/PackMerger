package sh.pcx.packmerger.merge;

import com.google.gson.*;
import org.bukkit.Bukkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;

/**
 * Validates a merged resource pack zip for structural correctness and missing references.
 *
 * <p>Validation is run after every merge to provide early warning about issues that could
 * cause problems in-game. The validator checks:</p>
 *
 * <ol>
 *   <li><strong>pack.mcmeta</strong> — must exist and contain a valid {@code pack.pack_format} field</li>
 *   <li><strong>JSON syntax</strong> — all {@code .json} files are parsed to detect syntax errors</li>
 *   <li><strong>Texture references</strong> — model files referencing textures are checked to ensure
 *       the referenced {@code .png} files exist in the pack</li>
 *   <li><strong>Model references</strong> — blockstate files referencing models are checked to ensure
 *       the referenced model JSON files exist in the pack</li>
 * </ol>
 *
 * <p>Issues are categorized as errors (critical, pack may not work) or warnings (non-critical,
 * pack may have visual glitches). Results are logged to console and returned as a
 * {@link ValidationResult} for display in the validate command.</p>
 *
 * <p>Called from {@link PackMerger#mergeAndUpload} after the merge completes. Runs on an
 * async thread and does not interact with the Bukkit API.</p>
 *
 * @see PackMergeEngine
 * @see PackMerger#mergeAndUpload(org.bukkit.command.CommandSender)
 */
public class PackValidator {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMergerBootstrap plugin;

    /** Colored console logger. */
    private final PluginLogger logger;

    /**
     * Creates a new pack validator.
     *
     * @param plugin the owning PackMergerBootstrap plugin
     */
    public PackValidator(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
    }

    /**
     * Immutable result of a validation run, containing warning/error counts and messages.
     *
     * @param warnings number of non-critical issues found
     * @param errors   number of critical issues found
     * @param messages list of human-readable issue descriptions, prefixed with "WARNING:" or "ERROR:"
     */
    public record ValidationResult(int warnings, int errors, List<String> messages) {}

    /**
     * Validates the given merged pack zip file.
     *
     * <p>Opens the zip and performs all validation checks in a single pass where possible.
     * Multiple passes over the zip entries are needed because later checks (texture/model
     * references) require the complete set of paths to be known first.</p>
     *
     * @param packFile the merged pack zip file to validate
     * @return a {@link ValidationResult} with the counts and messages
     */
    public ValidationResult validate(File packFile) {
        int warnings = 0;
        int errors = 0;
        List<String> messages = new ArrayList<>();

        if (!packFile.exists()) {
            logger.error("Validation: Merged pack file does not exist!");
            return new ValidationResult(0, 1, List.of("Merged pack file does not exist"));
        }

        try (ZipFile zf = new ZipFile(packFile)) {
            // First pass: collect all file paths in the pack for reference checking later
            Set<String> allPaths = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    allPaths.add(entry.getName().replace('\\', '/'));
                }
            }

            // Check 1: Validate pack.mcmeta presence and structure
            if (!allPaths.contains("pack.mcmeta")) {
                errors++;
                String msg = "ERROR: pack.mcmeta is missing from merged pack";
                messages.add(msg);
                logger.error("Validation: " + msg);
            } else {
                ZipEntry mcmetaEntry = zf.getEntry("pack.mcmeta");
                try (InputStream is = zf.getInputStream(mcmetaEntry)) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject mcmeta = JsonParser.parseString(content).getAsJsonObject();
                    // Minecraft requires a "pack" object declaring its format: either the
                    // legacy "pack_format" or, since 1.21.9 (26.1 packs), "min_format" /
                    // "max_format", which make "pack_format" optional.
                    JsonObject packBlock = mcmeta.has("pack") && mcmeta.get("pack").isJsonObject()
                            ? mcmeta.getAsJsonObject("pack") : null;
                    if (resolveFormatDeclaration(packBlock) == null) {
                        errors++;
                        String msg = "ERROR: pack.mcmeta is missing 'pack.pack_format' "
                                + "(or 'pack.min_format'/'pack.max_format') field";
                        messages.add(msg);
                        logger.error("Validation: " + msg);
                    } else {
                        // Check 1b: pack_format drift vs running server version
                        errors += checkPackFormat(mcmeta.getAsJsonObject("pack"), messages);
                    }
                } catch (JsonSyntaxException e) {
                    errors++;
                    String msg = "ERROR: pack.mcmeta contains invalid JSON";
                    messages.add(msg);
                    logger.error("Validation: " + msg);
                }
            }

            // Check 2: Validate all JSON files for syntax errors
            entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');

                if (path.endsWith(".json")) {
                    try (InputStream is = zf.getInputStream(entry)) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        // Attempt to parse — throws JsonSyntaxException on invalid JSON
                        JsonParser.parseString(content);
                    } catch (JsonSyntaxException e) {
                        warnings++;
                        String msg = "WARNING: Invalid JSON: " + path + " — " + e.getMessage();
                        messages.add(msg);
                        logger.warning("Validation: " + msg);
                    }
                }
            }

            // Check 3: Validate model texture references
            entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/').toLowerCase();

                // Match: assets/<namespace>/models/<any>.json
                if (path.matches("assets/[^/]+/models/.+\\.json")) {
                    try (InputStream is = zf.getInputStream(entry)) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject model = JsonParser.parseString(content).getAsJsonObject();

                        // Check each texture reference in the model's "textures" block
                        if (model.has("textures") && model.get("textures").isJsonObject()) {
                            JsonObject textures = model.getAsJsonObject("textures");
                            for (Map.Entry<String, JsonElement> tex : textures.entrySet()) {
                                if (!tex.getValue().isJsonPrimitive()) continue;
                                String ref = tex.getValue().getAsString();
                                // Skip variable references like #layer0 — these refer to
                                // other texture slots within the same model, not actual files
                                if (ref.startsWith("#")) continue;
                                // Verify the referenced .png texture exists in the pack
                                if (!textureExists(allPaths, ref)) {
                                    warnings++;
                                    String msg = "WARNING: Model " + entry.getName() + " references missing texture: " + ref;
                                    messages.add(msg);
                                    logger.debug("Validation: " + msg);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // JSON syntax errors were already reported in Check 2
                    }
                }

                // Check 4: Validate blockstate model references
                // Match: assets/<namespace>/blockstates/<any>.json
                if (path.matches("assets/[^/]+/blockstates/.+\\.json")) {
                    try (InputStream is = zf.getInputStream(entry)) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject blockstate = JsonParser.parseString(content).getAsJsonObject();

                        checkBlockstateModels(blockstate, entry.getName(), allPaths, messages);
                    } catch (Exception e) {
                        // JSON syntax errors were already reported in Check 2
                    }
                }
            }

        } catch (IOException e) {
            errors++;
            String msg = "ERROR: Could not open merged pack for validation: " + e.getMessage();
            messages.add(msg);
            logger.error("Validation: " + msg);
        }

        // Check 5: Orphan asset detection (unreferenced textures/sounds in the output).
        // Reported as warnings so it never blocks shipping.
        if (plugin.getConfigManager().isDetectOrphans()) {
            try (ZipFile zf = new ZipFile(packFile)) {
                int limit = plugin.getConfigManager().getOrphanReportLimit();
                OrphanDetector.OrphanReport report = OrphanDetector.scan(zf, limit);
                if (report.count() > 0) {
                    messages.add("WARNING: " + report.count() + " orphan asset(s) in output (~"
                            + formatBytes(report.totalBytes()) + " of unused data). "
                            + "Top " + Math.min(limit, report.topLargest().size())
                            + " by size:");
                    for (OrphanDetector.Orphan o : report.topLargest()) {
                        messages.add("WARNING:   " + o.path() + " (" + formatBytes(o.size()) + ")");
                    }
                    logger.warning("Validation: " + report.count() + " orphan assets ("
                            + formatBytes(report.totalBytes()) + " bloat)");
                } else {
                    logger.debug("Validation: no orphan assets detected");
                }
            } catch (IOException e) {
                logger.debug("Validation: orphan scan failed: " + e.getMessage());
            }
        }

        // Recount warnings from messages since checkBlockstateModels adds directly to the list
        warnings = (int) messages.stream().filter(m -> m.startsWith("WARNING:")).count();

        logger.info("Validation complete: " + warnings + " warnings, " + errors + " errors");
        return new ValidationResult(warnings, errors, messages);
    }

    /** Compact human-readable byte count for orphan reports. */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Compares the merged pack's {@code pack_format} to what the running Minecraft
     * version expects, honoring the {@code validation.pack-format-check} mode:
     * {@code off} = skip entirely, {@code warn} = append a WARNING:, {@code error}
     * = append an ERROR: and return 1 so the caller increments its error counter.
     *
     * <p>Uses {@link PackFormatRegistry} as the source of truth for version →
     * format mapping. If the server version isn't in the registry the check
     * silently skips (no warning) so a new MC version doesn't spam false reports.</p>
     *
     * @return number of errors emitted (0 or 1)
     */
    private int checkPackFormat(JsonObject packBlock, List<String> messages) {
        String mode = plugin.getConfigManager().getPackFormatCheckMode();
        if (mode == null || mode.equalsIgnoreCase("off")) return 0;

        FormatDecl decl = resolveFormatDeclaration(packBlock);
        if (decl == null) return 0;  // no recognizable format info — can't check

        String mcVersion = getMinecraftVersion();
        PackFormatRegistry.Drift drift =
                PackFormatRegistry.classify(decl.declaredFormat(), decl.supportedFormats(), mcVersion);

        if (drift == PackFormatRegistry.Drift.MATCH || drift == PackFormatRegistry.Drift.UNKNOWN) {
            return 0;
        }

        int expected = PackFormatRegistry.forMinecraftVersion(mcVersion);
        String base = "pack format " + decl.declaredFormat() + " in pack.mcmeta doesn't match server "
                + mcVersion + " (expected " + expected + ")";

        if (mode.equalsIgnoreCase("error") || drift == PackFormatRegistry.Drift.MAJOR) {
            String msg = "ERROR: " + base + " — " + drift.name().toLowerCase() + " drift";
            messages.add(msg);
            logger.error("Validation: " + msg);
            return 1;
        } else {
            String msg = "WARNING: " + base + " — " + drift.name().toLowerCase() + " drift";
            messages.add(msg);
            logger.warning("Validation: " + msg);
            return 0;
        }
    }

    /**
     * Accepts the three valid JSON shapes for {@code supported_formats}: an int
     * (treated as [n, n]), an array {@code [min, max]}, or an object
     * {@code {"min_inclusive": m, "max_inclusive": n}}. Returns {@code null}
     * for absent or malformed inputs.
     */
    private static int[] extractSupportedFormats(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            int n = el.getAsInt();
            return new int[] { n, n };
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() == 2 && arr.get(0).isJsonPrimitive() && arr.get(1).isJsonPrimitive()) {
                return new int[] { arr.get(0).getAsInt(), arr.get(1).getAsInt() };
            }
            return null;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("min_inclusive") && obj.has("max_inclusive")) {
                return new int[] { obj.get("min_inclusive").getAsInt(), obj.get("max_inclusive").getAsInt() };
            }
        }
        return null;
    }

    /** A pack's declared format and supported {@code [min, max]} range. */
    record FormatDecl(int declaredFormat, int[] supportedFormats) {}

    /**
     * Resolves the pack's declared format + supported range, honoring both schemas:
     * the legacy {@code pack_format} / {@code supported_formats}, and the 1.21.9+
     * (26.1) {@code min_format} / {@code max_format} fields that replaced it. The
     * new fields are treated as the supported range, with {@code max_format} as the
     * "declared" format for the drift message. Returns {@code null} when the pack
     * declares no recognizable format info.
     */
    static FormatDecl resolveFormatDeclaration(JsonObject packBlock) {
        if (packBlock == null) return null;
        Integer min = readFormatInt(packBlock.get("min_format"));
        Integer max = readFormatInt(packBlock.get("max_format"));
        if (min != null || max != null) {
            int lo = min != null ? min : max;
            int hi = max != null ? max : min;
            return new FormatDecl(hi, new int[] { lo, hi });
        }
        Integer legacy = readFormatInt(packBlock.get("pack_format"));
        if (legacy == null) return null;
        return new FormatDecl(legacy, extractSupportedFormats(packBlock.get("supported_formats")));
    }

    /**
     * Reads a pack-format value that may be a plain integer or, since 1.21.9, a
     * {@code [major, minor]} array (the major component is used). Returns
     * {@code null} for absent or malformed values.
     */
    static Integer readFormatInt(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) return el.getAsInt();
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() >= 1 && arr.get(0).isJsonPrimitive() && arr.get(0).getAsJsonPrimitive().isNumber()) {
                return arr.get(0).getAsInt();
            }
        }
        return null;
    }

    /**
     * @return the running Minecraft version string (e.g. {@code "1.21.4"}),
     *         preferring Paper's {@code Bukkit.getMinecraftVersion()} and
     *         falling back to parsing {@code Bukkit.getBukkitVersion()} if
     *         the preferred method isn't available
     */
    private static String getMinecraftVersion() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            // Fallback for non-Paper servers — parse "1.21.4-R0.1-SNAPSHOT"
            String bukkit = Bukkit.getBukkitVersion();
            int dash = bukkit.indexOf('-');
            return dash > 0 ? bukkit.substring(0, dash) : bukkit;
        }
    }

    /**
     * Checks whether a texture referenced by a model exists in the pack.
     *
     * <p>Texture references in Minecraft models use the format {@code "namespace:path"}
     * (e.g. {@code "minecraft:block/stone"}) or just {@code "path"} which defaults to
     * the {@code minecraft} namespace. The actual file is located at
     * {@code assets/<namespace>/textures/<path>.png}.</p>
     *
     * @param allPaths   set of all file paths in the pack
     * @param textureRef the texture reference string from the model JSON
     * @return {@code true} if the referenced texture file exists
     */
    private boolean textureExists(Set<String> allPaths, String textureRef) {
        // Parse the "namespace:path" format
        String namespace;
        String path;
        if (textureRef.contains(":")) {
            String[] parts = textureRef.split(":", 2);
            namespace = parts[0];
            path = parts[1];
        } else {
            // No namespace specified — defaults to "minecraft"
            namespace = "minecraft";
            path = textureRef;
        }

        // Construct the expected file path and check both exact and lowercase matches
        String texturePath = "assets/" + namespace + "/textures/" + path + ".png";
        return allPaths.contains(texturePath) || allPaths.contains(texturePath.toLowerCase());
    }

    /**
     * Checks whether a model referenced by a blockstate exists in the pack.
     *
     * <p>Model references use the same {@code "namespace:path"} format as texture
     * references. The actual file is at {@code assets/<namespace>/models/<path>.json}.</p>
     *
     * @param allPaths set of all file paths in the pack
     * @param modelRef the model reference string from the blockstate JSON
     * @return {@code true} if the referenced model file exists
     */
    private boolean modelExists(Set<String> allPaths, String modelRef) {
        String namespace;
        String path;
        if (modelRef.contains(":")) {
            String[] parts = modelRef.split(":", 2);
            namespace = parts[0];
            path = parts[1];
        } else {
            namespace = "minecraft";
            path = modelRef;
        }

        String modelPath = "assets/" + namespace + "/models/" + path + ".json";
        return allPaths.contains(modelPath) || allPaths.contains(modelPath.toLowerCase());
    }

    /**
     * Validates all model references within a blockstate JSON object.
     *
     * <p>Blockstates can use two formats:</p>
     * <ul>
     *   <li><strong>"variants" format</strong> — a map of state strings to model references</li>
     *   <li><strong>"multipart" format</strong> — an array of condition/apply pairs</li>
     * </ul>
     *
     * <p>Each referenced model is checked for existence via {@link #modelExists}.</p>
     *
     * @param blockstate the parsed blockstate JSON object
     * @param fileName   the blockstate file name (for error messages)
     * @param allPaths   set of all file paths in the pack
     * @param messages   list to append warning messages to
     */
    private void checkBlockstateModels(JsonObject blockstate, String fileName, Set<String> allPaths, List<String> messages) {
        // Handle "variants" format: { "variants": { "facing=north": { "model": "..." } } }
        if (blockstate.has("variants") && blockstate.get("variants").isJsonObject()) {
            for (Map.Entry<String, JsonElement> variant : blockstate.getAsJsonObject("variants").entrySet()) {
                checkModelRef(variant.getValue(), fileName, allPaths, messages);
            }
        }

        // Handle "multipart" format: { "multipart": [ { "apply": { "model": "..." } } ] }
        if (blockstate.has("multipart") && blockstate.get("multipart").isJsonArray()) {
            for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                    checkModelRef(part.getAsJsonObject().get("apply"), fileName, allPaths, messages);
                }
            }
        }
    }

    /**
     * Checks a single model reference element from a blockstate definition.
     *
     * <p>The element can be either a JSON object with a "model" field, or an array of
     * such objects (for weighted random variants). Handles both cases recursively.</p>
     *
     * @param element  the JSON element to check (object or array)
     * @param fileName the blockstate file name (for error messages)
     * @param allPaths set of all file paths in the pack
     * @param messages list to append warning messages to
     */
    private void checkModelRef(JsonElement element, String fileName, Set<String> allPaths, List<String> messages) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("model") && obj.get("model").isJsonPrimitive()) {
                String modelRef = obj.get("model").getAsString();
                if (!modelExists(allPaths, modelRef)) {
                    String msg = "WARNING: Blockstate " + fileName + " references missing model: " + modelRef;
                    messages.add(msg);
                    logger.debug("Validation: " + msg);
                }
            }
        } else if (element.isJsonArray()) {
            // Array of weighted model variants — check each one
            for (JsonElement item : element.getAsJsonArray()) {
                checkModelRef(item, fileName, allPaths, messages);
            }
        }
    }
}
