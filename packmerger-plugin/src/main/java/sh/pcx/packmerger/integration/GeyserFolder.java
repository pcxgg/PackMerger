package sh.pcx.packmerger.integration;

import java.io.File;
import java.util.List;

/**
 * Finds Geyser's data folder for {@code bedrock.auto-deploy-to-geyser}. Pure, so it is
 * unit tested; the plugin passes the data folders of installed Geyser plugins.
 */
public final class GeyserFolder {

    /** Folder names Geyser uses under {@code plugins/} on Paper/Spigot, tried in order. */
    public static final List<String> KNOWN_NAMES = List.of("Geyser-Spigot", "Geyser-Paper");

    private GeyserFolder() {}

    /**
     * @param configured          {@code bedrock.geyser-folder} (absolute, or relative to the
     *                            server folder); blank = auto-detect
     * @param serverRoot          the server folder
     * @param pluginsFolder       {@code plugins/}
     * @param installedDataFolders data folders of installed plugins named {@code Geyser-*},
     *                            in load order
     * @return the folder to deploy into ({@code null} if auto-detection found nothing); a
     *         configured folder is returned even if it does not exist so the caller can
     *         name it in the warning
     */
    public static File resolve(String configured, File serverRoot, File pluginsFolder,
                               List<File> installedDataFolders) {
        if (configured != null && !configured.isBlank()) {
            File f = new File(configured);
            return f.isAbsolute() ? f : new File(serverRoot, configured);
        }
        for (File f : installedDataFolders) {
            if (f != null && f.isDirectory()) return f;
        }
        for (String name : KNOWN_NAMES) {
            File f = new File(pluginsFolder, name);
            if (f.isDirectory()) return f;
        }
        return null;
    }
}
