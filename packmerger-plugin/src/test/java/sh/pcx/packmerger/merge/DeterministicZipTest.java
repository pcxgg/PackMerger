package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicZipTest {

    private static Map<String, byte[]> content(boolean reversed) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(List.of(
                "pack.mcmeta", "assets/minecraft/lang/en_us.json", "assets/minecraft/textures/item/a.png"));
        if (reversed) java.util.Collections.reverse(names);
        for (String n : names) files.put(n, ("content of " + n).getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static String sha1(File f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(f.toPath())));
    }

    @Test
    void identicalContentGivesIdenticalSha1AcrossTimeAndInsertionOrder(@TempDir File tmp) throws Exception {
        File a = new File(tmp, "a.zip");
        File b = new File(tmp, "b.zip");
        DeterministicZip.write(a, content(false), 6);
        Thread.sleep(2_100); // DOS timestamps have 2 s resolution
        DeterministicZip.write(b, content(true), 6);
        assertEquals(sha1(a), sha1(b));
    }

    @Test
    void timeZoneDoesNotChangeTheBytes(@TempDir File tmp) throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            File a = new File(tmp, "a.zip");
            DeterministicZip.write(a, content(false), 6);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            File b = new File(tmp, "b.zip");
            DeterministicZip.write(b, content(false), 6);
            assertEquals(sha1(a), sha1(b));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void entriesAreSortedAndCarryTheFixedTimestamp(@TempDir File tmp) throws Exception {
        File a = new File(tmp, "a.zip");
        DeterministicZip.write(a, content(true), 6);
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(a)) {
            var it = zf.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                names.add(e.getName());
                assertEquals(DeterministicZip.FIXED_TIME, e.getTimeLocal());
                assertNull(e.getExtra(), "no extended-timestamp extra field expected");
            }
        }
        assertEquals(List.of("assets/minecraft/lang/en_us.json", "assets/minecraft/textures/item/a.png", "pack.mcmeta"), names);
    }

    @Test
    void differentContentGivesDifferentSha1(@TempDir File tmp) throws Exception {
        File a = new File(tmp, "a.zip");
        File b = new File(tmp, "b.zip");
        DeterministicZip.write(a, content(false), 6);
        Map<String, byte[]> changed = content(false);
        changed.put("pack.mcmeta", "changed".getBytes(StandardCharsets.UTF_8));
        DeterministicZip.write(b, changed, 6);
        assertNotEquals(sha1(a), sha1(b));
    }
}
