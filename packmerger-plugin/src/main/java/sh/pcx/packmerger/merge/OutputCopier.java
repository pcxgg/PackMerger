package sh.pcx.packmerger.merge;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies the merged zip into the folders listed in {@code merge.output-copy-to}, for
 * operators who serve it with something else (a web server folder, another plugin).
 * Each copy is written to a temp file in the target folder and moved into place, so a
 * reader never sees a half-written zip.
 */
public final class OutputCopier {

    /** Outcome of one target. */
    public record Result(String target, File file, boolean ok, String detail) {}

    private OutputCopier() {}

    /**
     * @param output        the merged zip
     * @param targets       folders (absolute, or relative to {@code plugins/})
     * @param pluginsFolder {@code plugins/}
     * @param packsFolder   {@code plugins/PackMerger/packs/}: copying there would merge the
     *                      output into the next merge, so such targets are refused
     */
    public static List<Result> copy(File output, List<String> targets, File pluginsFolder, File packsFolder) {
        List<Result> out = new ArrayList<>();
        Path packs = packsFolder.getAbsoluteFile().toPath().normalize();
        for (String target : targets) {
            if (target == null || target.isBlank()) continue;
            File dir = new File(target);
            if (!dir.isAbsolute()) dir = new File(pluginsFolder, target);
            Path dirPath = dir.getAbsoluteFile().toPath().normalize();
            File dest = dirPath.resolve(output.getName()).toFile();
            if (dirPath.startsWith(packs)) {
                out.add(new Result(target, dest, false,
                        "refused: it is inside packs/, so the output would be merged into the next merge"));
                continue;
            }
            try {
                Files.createDirectories(dirPath);
                Path tmp = Files.createTempFile(dirPath, ".packmerger-", ".tmp");
                try {
                    Files.copy(output.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(tmp, dest.toPath(), StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
                out.add(new Result(target, dest, true, "copied"));
            } catch (IOException | RuntimeException e) {
                out.add(new Result(target, dest, false, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return out;
    }
}
