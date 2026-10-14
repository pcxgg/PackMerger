package sh.pcx.packmerger.api;

/**
 * A pack registered through {@link PackMergerApi#registerPack}.
 *
 * @param alias  the name used in {@code priority:} and {@code /pm inspect}
 * @param owner  name of the plugin that registered it
 * @param source the folder/zip path or the download URL, as registered
 * @param remote {@code true} if {@code source} is a download URL
 */
public record RegisteredPack(String alias, String owner, String source, boolean remote) {}
