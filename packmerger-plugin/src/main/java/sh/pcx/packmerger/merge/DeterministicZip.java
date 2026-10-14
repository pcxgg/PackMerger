package sh.pcx.packmerger.merge;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes zip archives whose bytes depend only on their content.
 *
 * <p>{@code new ZipEntry(name)} stamps every entry with the current time, so two
 * merges of identical inputs used to produce zips with different SHA-1 hashes. That
 * made every merge look like a "new pack" (notify/resend spam, needless client
 * re-downloads, S3 object churn). Every entry written here gets the same fixed
 * timestamp, no extended-time extra field, and entries are written in sorted path
 * order, so identical content always yields an identical SHA-1.</p>
 */
public final class DeterministicZip {

    /**
     * Timestamp stamped on every entry: 1980-02-01 00:00 (the same constant Gradle uses
     * for reproducible archives). Stored as a local (zone-less) DOS time so the
     * server's time zone has no effect on the output bytes. 1980-01-01 itself is not
     * used because the JDK treats it as "before 1980" and adds a zone-dependent
     * extended-timestamp field.
     */
    public static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 2, 1, 0, 0, 0);

    private DeterministicZip() {}

    /** Creates an entry with the fixed timestamp. */
    public static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(FIXED_TIME);
        return entry;
    }

    /**
     * Writes {@code files} (path to bytes) to {@code outputFile} in sorted path order.
     *
     * @param compressionLevel the deflate level (0-9)
     */
    public static void write(File outputFile, Map<String, byte[]> files, int compressionLevel) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile))) {
            write(out, files, compressionLevel);
        }
    }

    /** Stream variant of {@link #write(File, Map, int)}; the stream is finished but not closed. */
    public static void write(OutputStream out, Map<String, byte[]> files, int compressionLevel) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out);
        zos.setLevel(compressionLevel);
        for (Map.Entry<String, byte[]> e : new TreeMap<>(files).entrySet()) {
            zos.putNextEntry(entry(e.getKey()));
            zos.write(e.getValue());
            zos.closeEntry();
        }
        zos.finish();
    }
}
