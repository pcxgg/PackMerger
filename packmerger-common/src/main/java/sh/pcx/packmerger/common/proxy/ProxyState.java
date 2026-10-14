package sh.pcx.packmerger.common.proxy;

import sh.pcx.packmerger.common.PackInfo;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What the proxy has been told, persisted to {@code state.properties} so a proxy restart
 * keeps offering the last pushed pack instead of falling back to a stale config URL.
 *
 * <p>Not thread-safe; {@link ProxyCoordinator} guards it.</p>
 */
public final class ProxyState {

    /** File name inside the proxy plugin's data folder. */
    public static final String FILE_NAME = "state.properties";

    /**
     * A remembered pack.
     *
     * @param pack      URL and SHA-1
     * @param timestamp publish time the backend reported (0 = unknown)
     * @param source    proxy server name it came from, or {@code config.properties}
     */
    public record Entry(PackInfo pack, long timestamp, String source) {}

    /** The network pack (NETWORK mode), or {@code null}. */
    Entry network;

    /** The latest accepted pack per proxy server name. */
    final Map<String, Entry> servers = new TreeMap<>();

    /** {@code url|sha1} of config.properties when it was last applied. */
    String configFingerprint;

    public Entry network() {
        return network;
    }

    public Map<String, Entry> servers() {
        return java.util.Collections.unmodifiableMap(servers);
    }

    /** Reads {@code file}; a missing or unreadable file yields an empty state. */
    public static ProxyState load(Path file) throws IOException {
        ProxyState state = new ProxyState();
        if (!Files.isRegularFile(file)) return state;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        state.configFingerprint = p.getProperty("config.fingerprint");
        state.network = readEntry(p, "network");
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("server.") && key.endsWith(".url")) {
                String name = key.substring("server.".length(), key.length() - ".url".length());
                Entry e = readEntry(p, "server." + name);
                if (e != null) state.servers.put(name, e);
            }
        }
        return state;
    }

    /** Writes the state atomically (temp file + move). */
    public void save(Path file) throws IOException {
        Properties p = new Properties();
        if (configFingerprint != null) p.setProperty("config.fingerprint", configFingerprint);
        writeEntry(p, "network", network);
        servers.forEach((name, e) -> writeEntry(p, "server." + name, e));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(out, "PackMerger proxy state: the last pack each backend published. "
                    + "Managed by the plugin; edit config.properties instead.");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Entry readEntry(Properties p, String prefix) {
        String url = p.getProperty(prefix + ".url", "").trim();
        if (url.isEmpty()) return null;
        String sha1 = p.getProperty(prefix + ".sha1", "").trim();
        long ts;
        try {
            ts = Long.parseLong(p.getProperty(prefix + ".timestamp", "0").trim());
        } catch (NumberFormatException e) {
            ts = 0L;
        }
        return new Entry(new PackInfo(url, sha1.isEmpty() ? null : sha1), ts, p.getProperty(prefix + ".source", ""));
    }

    private static void writeEntry(Properties p, String prefix, Entry e) {
        if (e == null || !e.pack().hasUrl()) return;
        p.setProperty(prefix + ".url", e.pack().url());
        p.setProperty(prefix + ".sha1", e.pack().hasHash() ? e.pack().sha1Hex() : "");
        p.setProperty(prefix + ".timestamp", Long.toString(e.timestamp()));
        p.setProperty(prefix + ".source", e.source() == null ? "" : e.source());
    }
}
