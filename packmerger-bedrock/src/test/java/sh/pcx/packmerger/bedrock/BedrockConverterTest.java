package sh.pcx.packmerger.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BedrockConverterTest {

    @Test
    void convert_cmdItem_producesMcpackAndGeyserMappings(@TempDir Path tmp) throws IOException {
        Map<String, String> pack = new LinkedHashMap<>();
        pack.put("pack.mcmeta", "{\"pack\":{\"pack_format\":75}}");
        // Java 1.21.4+ item definition: paper, range_dispatch on custom_model_data.
        pack.put("assets/minecraft/items/paper.json", """
                {"model":{"type":"minecraft:range_dispatch","property":"minecraft:custom_model_data",
                "fallback":{"type":"minecraft:model","model":"minecraft:item/paper"},
                "entries":[{"threshold":7,"model":{"type":"minecraft:model","model":"test:item/cool"}}]}}""");
        pack.put("assets/test/models/item/cool.json",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:item/cool\"}}");
        Map<String, byte[]> bin = new LinkedHashMap<>();
        bin.put("assets/test/textures/item/cool.png", new byte[]{1, 2, 3, 4});

        Path zip = tmp.resolve("merged.zip");
        writeZip(zip, pack, bin);

        Path out = tmp.resolve("out");
        BedrockConversionResult result =
                new BedrockConverter(new BedrockConverterOptions("TestPack", false)).convert(zip, out);

        assertTrue(result.producedAnything());
        assertEquals(1, result.itemsConverted());
        assertEquals(1, result.texturesCopied());
        assertNotNull(result.mcpackFile());
        assertTrue(Files.isRegularFile(result.mcpackFile()));

        // The .mcpack must contain a manifest, the item_texture map, and the texture.
        Map<String, byte[]> mc = readZip(result.mcpackFile());
        assertTrue(mc.containsKey("manifest.json"));
        assertTrue(mc.containsKey("textures/item_texture.json"));
        assertTrue(mc.containsKey("textures/items/test_item_cool.png"));
        JsonObject itemTexture = JsonParser.parseString(new String(mc.get("textures/item_texture.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(itemTexture.getAsJsonObject("texture_data").has("test_item_cool"));

        // Geyser mappings must map minecraft:paper cmd 7 -> the icon key.
        JsonObject mappings = JsonParser.parseString(Files.readString(result.geyserMappingsFile()))
                .getAsJsonObject();
        var entries = mappings.getAsJsonObject("items").getAsJsonArray("minecraft:paper");
        assertEquals(1, entries.size());
        JsonObject e = entries.get(0).getAsJsonObject();
        assertEquals(7, e.get("custom_model_data").getAsInt());
        assertEquals("test_item_cool", e.get("icon").getAsString());
    }

    @Test
    void convert_threeDModelWithoutLayer0_isSkippedWithWarning(@TempDir Path tmp) throws IOException {
        Map<String, String> pack = new LinkedHashMap<>();
        pack.put("assets/minecraft/items/diamond.json", """
                {"model":{"type":"minecraft:range_dispatch","property":"minecraft:custom_model_data",
                "entries":[{"threshold":1,"model":{"type":"minecraft:model","model":"test:block/statue"}}]}}""");
        // A 3D-style model: elements but no textures map we can resolve to a 2D icon.
        pack.put("assets/test/models/block/statue.json", "{\"elements\":[]}");

        Path zip = tmp.resolve("merged.zip");
        writeZip(zip, pack, Map.of());

        BedrockConversionResult result =
                new BedrockConverter(new BedrockConverterOptions("TestPack", true)).convert(zip, tmp.resolve("out"));

        assertEquals(0, result.itemsConverted());
        assertNull(result.mcpackFile());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void manifestVersionFollowsTheContent(@TempDir Path tmp) throws IOException {
        Map<String, String> pack = new LinkedHashMap<>();
        pack.put("assets/minecraft/items/paper.json", """
                {"model":{"type":"minecraft:range_dispatch","property":"minecraft:custom_model_data",
                "entries":[{"threshold":7,"model":{"type":"minecraft:model","model":"test:item/cool"}}]}}""");
        pack.put("assets/test/models/item/cool.json",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:item/cool\"}}");
        BedrockConverter conv = new BedrockConverter(new BedrockConverterOptions("TestPack", false));

        Path zip1 = tmp.resolve("a.zip");
        writeZip(zip1, pack, Map.of("assets/test/textures/item/cool.png", new byte[]{1, 2, 3}));
        BedrockConversionResult first = conv.convert(zip1, tmp.resolve("out1"));
        BedrockConversionResult same = conv.convert(zip1, tmp.resolve("out2"));
        Path zip2 = tmp.resolve("b.zip");
        writeZip(zip2, pack, Map.of("assets/test/textures/item/cool.png", new byte[]{9, 9, 9}));
        BedrockConversionResult changed = conv.convert(zip2, tmp.resolve("out3"));

        assertEquals(first.manifestVersion(), same.manifestVersion(), "same content, same version");
        assertNotEquals(first.manifestVersion(), changed.manifestVersion(), "new texture, new version");
        assertTrue(first.manifestVersion().matches("1\\.\\d+\\.\\d+"), first.manifestVersion());

        JsonObject manifest = JsonParser.parseString(new String(readZip(changed.mcpackFile()).get("manifest.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        String header = manifest.getAsJsonObject("header").get("version").toString();
        String module = manifest.getAsJsonArray("modules").get(0).getAsJsonObject().get("version").toString();
        assertEquals("[" + changed.manifestVersion().replace('.', ',') + "]", header);
        assertEquals(header, module);
        // The UUIDs stay fixed so Geyser/clients treat it as the same pack, updated.
        assertEquals(JsonParser.parseString(new String(readZip(first.mcpackFile()).get("manifest.json"),
                        StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("header").get("uuid"),
                manifest.getAsJsonObject("header").get("uuid"));
    }

    // --- helpers ---

    private static void writeZip(Path zip, Map<String, String> text, Map<String, byte[]> bin) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> e : text.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : bin.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }

    private static Map<String, byte[]> readZip(Path zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                out.put(entry.getName(), zf.getInputStream(entry).readAllBytes());
            }
        }
        return out;
    }
}
