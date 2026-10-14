package sh.pcx.packmerger.bedrock;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of a {@link BedrockConverter} run.
 *
 * @param mcpackFile          the generated Bedrock pack ({@code .mcpack}), or {@code null} if nothing was produced
 * @param geyserMappingsFile  the generated Geyser custom-item mappings JSON, or {@code null}
 * @param itemsConverted      number of custom-model-data entries mapped
 * @param texturesCopied      number of textures copied into the Bedrock pack
 * @param warnings            items/models skipped or otherwise notable (e.g. 3D models, unresolved textures)
 * @param manifestVersion     the {@code .mcpack} manifest version, derived from its content (e.g.
 *                            {@code "1.4821.903"}), or {@code null} if nothing was produced
 */
public record BedrockConversionResult(
        Path mcpackFile,
        Path geyserMappingsFile,
        int itemsConverted,
        int texturesCopied,
        List<String> warnings,
        String manifestVersion) {

    public boolean producedAnything() {
        return mcpackFile != null && itemsConverted > 0;
    }
}
