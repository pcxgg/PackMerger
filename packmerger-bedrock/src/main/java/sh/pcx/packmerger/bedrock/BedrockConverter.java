package sh.pcx.packmerger.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Converts the custom items in a merged <em>Java</em> resource pack into a Bedrock
 * resource pack plus a <a href="https://wiki.geysermc.org/geyser/custom-items/">Geyser
 * custom-item mappings</a> file, so Bedrock (Floodgate) players see the same custom
 * item icons as Java players.
 *
 * <h2>Scope</h2>
 * This is the <strong>items-definition subset</strong>: it handles 1.21.4+ item
 * definitions ({@code assets/<ns>/items/<id>.json}) whose model is a
 * {@code minecraft:range_dispatch} on {@code minecraft:custom_model_data}, mapping each
 * threshold to the {@code layer0} texture of a {@code minecraft:model} entry. That covers
 * flat (2D) custom item icons — the common case. <strong>3D block/geometry models and
 * non-CMD selectors are not converted</strong>; they are recorded as warnings.
 *
 * <p>Pure file/JSON logic — no Bukkit/Geyser API — so it is unit-testable. The plugin
 * layer handles config, deploying outputs into Geyser's folders, and firing events.</p>
 */
public final class BedrockConverter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final BedrockConverterOptions options;

    public BedrockConverter(BedrockConverterOptions options) {
        this.options = options;
    }

    /**
     * Converts the given merged Java pack.
     *
     * @param mergedPackZip the merged Java resource pack ({@code .zip})
     * @param outputDir     directory to write the {@code .mcpack} and mappings JSON into
     * @return the conversion result (with {@code mcpackFile == null} when no convertible items were found)
     * @throws IOException on read/write failure
     */
    public BedrockConversionResult convert(Path mergedPackZip, Path outputDir) throws IOException {
        Map<String, byte[]> entries = readZip(mergedPackZip);
        List<String> warnings = new ArrayList<>();

        Map<String, byte[]> bedrockTextures = new LinkedHashMap<>(); // bedrock path -> bytes
        JsonObject textureData = new JsonObject();                   // item_texture.json texture_data
        Map<String, JsonArray> mappingsByItem = new LinkedHashMap<>(); // base item -> geyser entries
        int items = 0;

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String[] nsId = parseItemDefPath(e.getKey());
            if (nsId == null) continue;
            String baseItem = nsId[0] + ":" + nsId[1];

            JsonObject root;
            try {
                root = JsonParser.parseString(new String(e.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException ex) {
                warnings.add("unparseable item definition: " + e.getKey());
                continue;
            }
            JsonElement modelEl = root.get("model");
            if (modelEl == null || !modelEl.isJsonObject()) continue;

            Map<Integer, String> cmdModels = extractCmdModels(modelEl.getAsJsonObject(), warnings, e.getKey());
            for (Map.Entry<Integer, String> cm : cmdModels.entrySet()) {
                int cmd = cm.getKey();
                String texRef = resolveLayer0(cm.getValue(), entries, warnings);
                if (texRef == null) continue;

                String texPath = "assets/" + namespaceOf(texRef) + "/textures/" + pathOf(texRef) + ".png";
                byte[] texBytes = entries.get(texPath);
                if (texBytes == null) {
                    warnings.add("texture not found: " + texPath + " (cmd " + cmd + " of " + baseItem + ")");
                    continue;
                }

                String key = sanitize(texRef);
                String bedrockTexPath = "textures/items/" + key + ".png";
                if (!bedrockTextures.containsKey(bedrockTexPath)) {
                    bedrockTextures.put(bedrockTexPath, texBytes);
                    JsonObject td = new JsonObject();
                    td.addProperty("textures", "textures/items/" + key);
                    textureData.add(key, td);
                }

                JsonObject entry = new JsonObject();
                entry.addProperty("name", sanitize(nsId[0] + "_" + nsId[1]) + "_cmd" + cmd);
                entry.addProperty("custom_model_data", cmd);
                entry.addProperty("icon", key);
                entry.addProperty("allow_offhand", true);
                mappingsByItem.computeIfAbsent(baseItem, k -> new JsonArray()).add(entry);
                items++;
            }
        }

        if (items == 0) {
            return new BedrockConversionResult(null, null, 0, 0, warnings, null);
        }

        Files.createDirectories(outputDir);
        String base = sanitizeFile(options.packName());
        Path mcpack = outputDir.resolve(base + ".mcpack");
        byte[] itemTextureJson = itemTextureJson(textureData);
        byte[] mappingsJson = mappingsJson(mappingsByItem);
        int[] version = contentVersion(itemTextureJson, bedrockTextures, entries.get("pack.png"), mappingsJson);
        writeMcpack(mcpack, itemTextureJson, bedrockTextures, entries.get("pack.png"), version);
        Path mappings = outputDir.resolve(base + ".geyser.json");
        Files.write(mappings, mappingsJson);

        return new BedrockConversionResult(mcpack, mappings, items, bedrockTextures.size(), warnings,
                version[0] + "." + version[1] + "." + version[2]);
    }

    // ------------------------------------------------------------------ parsing

    /** @return {@code [namespace, id]} for an {@code assets/<ns>/items/<id>.json}, else {@code null} */
    static String[] parseItemDefPath(String path) {
        String[] p = path.split("/");
        if (p.length != 4) return null;
        if (!p[0].equals("assets") || !p[2].equals("items")) return null;
        if (!p[3].endsWith(".json")) return null;
        return new String[]{p[1], p[3].substring(0, p[3].length() - ".json".length())};
    }

    /**
     * Pulls {@code custom_model_data -> model reference} from a 1.21.4+ item-definition
     * model node. Only {@code range_dispatch} on {@code custom_model_data} with plain
     * {@code minecraft:model} entries is supported; anything else is warned and skipped.
     */
    static Map<Integer, String> extractCmdModels(JsonObject model, List<String> warnings, String path) {
        Map<Integer, String> out = new LinkedHashMap<>();
        String type = stripNs(asString(model.get("type")));
        if (!"range_dispatch".equals(type)) {
            warnings.add("skipped " + path + ": model selector '" + type + "' not supported (subset handles range_dispatch only)");
            return out;
        }
        if (!"custom_model_data".equals(stripNs(asString(model.get("property"))))) {
            warnings.add("skipped " + path + ": range_dispatch on non-custom_model_data property");
            return out;
        }
        JsonElement entriesEl = model.get("entries");
        if (entriesEl == null || !entriesEl.isJsonArray()) return out;
        for (JsonElement el : entriesEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            JsonElement threshold = entry.get("threshold");
            JsonElement inner = entry.get("model");
            if (threshold == null || inner == null || !inner.isJsonObject()) continue;
            String ref = extractModelRef(inner.getAsJsonObject());
            if (ref == null) {
                warnings.add("skipped a cmd entry in " + path + ": composite/non-model selector");
                continue;
            }
            try {
                out.put(threshold.getAsInt(), ref);
            } catch (RuntimeException ignored) {
                // non-integer threshold — skip
            }
        }
        return out;
    }

    private static String extractModelRef(JsonObject modelNode) {
        if (!"model".equals(stripNs(asString(modelNode.get("type"))))) return null;
        return asString(modelNode.get("model"));
    }

    /** Resolves a model reference to its {@code layer0} (or first) texture reference. */
    static String resolveLayer0(String modelRef, Map<String, byte[]> entries, List<String> warnings) {
        if (modelRef == null) return null;
        String modelPath = "assets/" + namespaceOf(modelRef) + "/models/" + pathOf(modelRef) + ".json";
        byte[] bytes = entries.get(modelPath);
        if (bytes == null) {
            warnings.add("model not found: " + modelPath);
            return null;
        }
        JsonObject model;
        try {
            model = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException ex) {
            warnings.add("unparseable model: " + modelPath);
            return null;
        }
        JsonElement texEl = model.get("textures");
        if (texEl == null || !texEl.isJsonObject()) {
            warnings.add("model has no textures (3D/parented not supported in subset): " + modelPath);
            return null;
        }
        JsonObject textures = texEl.getAsJsonObject();
        JsonElement layer0 = textures.get("layer0");
        if (layer0 != null) return asString(layer0);
        // Fall back to the first texture value if no layer0 (best-effort).
        for (Map.Entry<String, JsonElement> t : textures.entrySet()) {
            String v = asString(t.getValue());
            if (v != null) return v;
        }
        warnings.add("model has empty textures: " + modelPath);
        return null;
    }

    // ------------------------------------------------------------------ writing

    private byte[] itemTextureJson(JsonObject textureData) {
        JsonObject itemTexture = new JsonObject();
        itemTexture.addProperty("resource_pack_name", options.packName());
        itemTexture.addProperty("texture_name", "atlas.items");
        itemTexture.add("texture_data", textureData);
        return GSON.toJson(itemTexture).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Manifest version derived from the converted content. Bedrock clients (and Geyser)
     * cache a pack by UUID + version, and the UUIDs here are fixed per pack name, so a
     * fixed version would let clients keep stale icons. {@code [1, a, b]} with {@code a}
     * and {@code b} taken from a SHA-256 of everything that ends up in the pack (and the
     * mappings): the same content always gets the same version, changed content a new one.
     */
    static int[] contentVersion(byte[] itemTextureJson, Map<String, byte[]> textures, byte[] packIcon,
                                byte[] mappingsJson) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            update(md, "item_texture.json", itemTextureJson);
            new java.util.TreeMap<>(textures).forEach((k, v) -> update(md, k, v));
            if (packIcon != null) update(md, "pack_icon.png", packIcon);
            update(md, "mappings", mappingsJson);
            byte[] h = md.digest();
            return new int[]{1, ((h[0] & 0xff) << 8) | (h[1] & 0xff), ((h[2] & 0xff) << 8) | (h[3] & 0xff)};
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(java.security.MessageDigest md, String name, byte[] bytes) {
        md.update(name.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        md.update(bytes);
    }

    private void writeMcpack(Path mcpack, byte[] itemTextureJson, Map<String, byte[]> textures, byte[] packIcon,
                             int[] version) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(mcpack))) {
            putEntry(zos, "manifest.json", GSON.toJson(buildManifest(version)).getBytes(StandardCharsets.UTF_8));
            putEntry(zos, "textures/item_texture.json", itemTextureJson);

            for (Map.Entry<String, byte[]> t : textures.entrySet()) {
                putEntry(zos, t.getKey(), t.getValue());
            }
            if (packIcon != null) {
                putEntry(zos, "pack_icon.png", packIcon);
            }
        }
    }

    private JsonObject buildManifest(int[] version) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);

        JsonObject header = new JsonObject();
        header.addProperty("name", options.packName());
        header.addProperty("description", "Bedrock pack generated by PackMerger");
        header.addProperty("uuid", deterministicUuid("header"));
        header.add("version", intArray(version[0], version[1], version[2]));
        header.add("min_engine_version", intArray(1, 21, 0));
        manifest.add("header", header);

        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", deterministicUuid("module"));
        module.add("version", intArray(version[0], version[1], version[2]));
        JsonArray modules = new JsonArray();
        modules.add(module);
        manifest.add("modules", modules);
        return manifest;
    }

    private static byte[] mappingsJson(Map<String, JsonArray> mappingsByItem) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1");
        JsonObject items = new JsonObject();
        mappingsByItem.forEach(items::add);
        root.add("items", items);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    private String deterministicUuid(String role) {
        return UUID.nameUUIDFromBytes((options.packName() + ":" + role).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static JsonArray intArray(int a, int b, int c) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        arr.add(c);
        return arr;
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }

    private static Map<String, byte[]> readZip(Path zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int read;
                while ((read = zis.read(buf)) != -1) bos.write(buf, 0, read);
                out.put(entry.getName(), bos.toByteArray());
            }
        }
        return out;
    }

    private static String asString(JsonElement el) {
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    /** Strips a {@code minecraft:} (or any) namespace prefix from a type/property id. */
    private static String stripNs(String s) {
        if (s == null) return null;
        int i = s.indexOf(':');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static String namespaceOf(String ref) {
        int i = ref.indexOf(':');
        return i >= 0 ? ref.substring(0, i) : "minecraft";
    }

    private static String pathOf(String ref) {
        int i = ref.indexOf(':');
        return i >= 0 ? ref.substring(i + 1) : ref;
    }

    private static String sanitize(String ref) {
        return ref.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private static String sanitizeFile(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return s.isBlank() ? "packmerger" : s;
    }
}
