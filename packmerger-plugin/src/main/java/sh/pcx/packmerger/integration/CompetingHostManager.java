package sh.pcx.packmerger.integration;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bukkit side of {@link CompetingHosts}: reads the installed item plugins' configs,
 * warns on the console and to admins on join, rewrites a plugin's config for
 * {@code /pm fixup <plugin>} (after a backup), and remembers {@code /pm fixup dismiss}
 * in {@code plugins/PackMerger/dismissed-warnings.yml}.
 */
public class CompetingHostManager {

    /** File (in PackMerger's data folder) holding dismissed finding ids. */
    public static final String DISMISSED_FILE = "dismissed-warnings.yml";
    private static final String DISMISSED_KEY = "competing-hosts";

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;

    /** Findings of the last scan, by target id (only installed plugins with a config). */
    private volatile Map<String, List<CompetingHosts.Finding>> findings = Map.of();
    /** Ids of the findings logged by the last scan, so unchanged findings are not re-logged. */
    private volatile Set<String> lastLogged = Set.of();

    public CompetingHostManager(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
    }

    private File pluginsFolder() {
        return plugin.getDataFolder().getAbsoluteFile().getParentFile();
    }

    /** @return the target's config file */
    public File configFile(CompetingHosts.Target t) {
        return new File(new File(pluginsFolder(), t.pluginName()), t.configFile());
    }

    private static boolean installed(CompetingHosts.Target t) {
        return Bukkit.getPluginManager().getPlugin(t.pluginName()) != null;
    }

    /**
     * Re-reads every installed target's config. Logs findings that are new since the
     * last scan (dismissed ones only as a one-line note). Thread-safe; does file I/O.
     */
    public synchronized void scan() {
        Map<String, List<CompetingHosts.Finding>> out = new LinkedHashMap<>();
        for (CompetingHosts.Target t : CompetingHosts.TARGETS) {
            if (!installed(t)) continue;
            File f = configFile(t);
            if (!f.isFile()) continue;
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(f);
            } catch (IOException | InvalidConfigurationException e) {
                logger.warning("[hosts] could not read " + t.pluginName() + "/" + t.configFile() + ": " + e.getMessage());
                continue;
            }
            out.put(t.id(), CompetingHosts.detect(t, yaml::get));
        }
        findings = out;

        Set<String> dismissed = loadDismissed();
        dismissedCache = dismissed;
        Set<String> now = new LinkedHashSet<>();
        int dismissedCount = 0;
        for (List<CompetingHosts.Finding> list : out.values()) {
            for (CompetingHosts.Finding fd : list) {
                now.add(fd.id());
                if (lastLogged.contains(fd.id())) continue;
                if (dismissed.contains(fd.id())) {
                    dismissedCount++;
                } else {
                    logger.warning("[hosts] " + fd.describe() + ". Fix it with /pm fixup " + fd.target().id()
                            + " (or /pm fixup dismiss).");
                }
            }
        }
        if (dismissedCount > 0) {
            logger.info("[hosts] " + dismissedCount + " dismissed pack-hosting warning(s); see /pm status");
        }
        lastLogged = now;
    }

    /** @return findings of the last scan per target id (installed plugins with a config only) */
    public Map<String, List<CompetingHosts.Finding>> getFindings() {
        return findings;
    }

    /** @return {@code true} if the finding was dismissed with {@code /pm fixup dismiss} */
    public boolean isDismissed(CompetingHosts.Finding f) {
        return cachedDismissed().contains(f.id());
    }

    /**
     * @return the admin join notice (one line per plugin with undismissed findings), or
     *         {@code null} when there is nothing to show
     */
    public List<String> joinNotice() {
        Set<String> dismissed = cachedDismissed();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<CompetingHosts.Finding>> e : findings.entrySet()) {
            List<String> keys = new ArrayList<>();
            CompetingHosts.Target target = null;
            for (CompetingHosts.Finding f : e.getValue()) {
                if (dismissed.contains(f.id())) continue;
                target = f.target();
                keys.add(f.key() + ": " + f.current());
            }
            if (target == null) continue;
            lines.add("[PackMerger] " + target.pluginName() + " is set up to host or protect its own pack ("
                    + String.join(", ", keys) + "). Run /pm fixup " + target.id()
                    + " to hand it to PackMerger, or /pm fixup dismiss to hide this.");
        }
        return lines;
    }

    /**
     * Remembers every current finding as dismissed. A finding that changes (another key,
     * another value) is shown again.
     *
     * @return how many findings were dismissed
     */
    public synchronized int dismissAll() throws IOException {
        Set<String> dismissed = loadDismissed();
        int before = dismissed.size();
        for (List<CompetingHosts.Finding> list : findings.values()) {
            for (CompetingHosts.Finding f : list) dismissed.add(f.id());
        }
        saveDismissed(dismissed);
        dismissedCache = dismissed;
        return dismissed.size() - before;
    }

    /** Result of {@code /pm fixup <plugin>}. */
    public record FixupResult(boolean changed, Map<String, Object> applied, File backup, String message) {}

    /**
     * Backs up the target's config, writes the fixes and rescans.
     *
     * @throws IOException if the config can't be read, backed up or written
     */
    public synchronized FixupResult fixup(CompetingHosts.Target t) throws IOException {
        FixupResult r = applyFixup(configFile(t), t);
        if (r.changed()) {
            logger.info("[hosts] /pm fixup " + t.id() + ": wrote " + r.applied() + " to " + t.pluginName() + "/"
                    + t.configFile() + " (backup: " + r.backup().getName() + ")");
        }
        scan();
        return r;
    }

    /**
     * Rewrites {@code cfg} (the config of {@code t}) with {@link CompetingHosts#fixes}:
     * backs it up next to itself first, keeps its comments (Bukkit's YAML writer), writes
     * through a temp file, and refuses when the file changed while it was being edited.
     * Static and Bukkit-server-free so it is unit tested.
     */
    static FixupResult applyFixup(File cfg, CompetingHosts.Target t) throws IOException {
        if (!cfg.isFile()) {
            return new FixupResult(false, Map.of(), null, t.pluginName() + "/" + t.configFile() + " not found");
        }
        byte[] original = Files.readAllBytes(cfg.toPath());
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(original, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException("cannot parse " + cfg.getName() + ": " + e.getMessage(), e);
        }
        List<CompetingHosts.Finding> found = CompetingHosts.detect(t, yaml::get);
        if (found.isEmpty()) {
            return new FixupResult(false, Map.of(), null, "nothing to change");
        }
        Map<String, Object> fixes = CompetingHosts.fixes(found);
        File backup = backupFile(cfg);
        Files.copy(cfg.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        fixes.forEach(yaml::set);

        Path pending = Files.createTempFile(cfg.getAbsoluteFile().getParentFile().toPath(), ".packmerger-", ".tmp");
        try {
            Files.writeString(pending, yaml.saveToString(), StandardCharsets.UTF_8);
            if (!Arrays.equals(original, Files.readAllBytes(cfg.toPath()))) {
                throw new IOException(cfg.getName() + " changed while it was being edited; run the command again");
            }
            try {
                Files.move(pending, cfg.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, cfg.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
        return new FixupResult(true, fixes, backup, "ok");
    }

    private static File backupFile(File cfg) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File backup = new File(cfg.getParentFile(), cfg.getName() + ".packmerger-backup-" + stamp);
        for (int i = 1; backup.exists(); i++) {
            backup = new File(cfg.getParentFile(), cfg.getName() + ".packmerger-backup-" + stamp + "-" + i);
        }
        return backup;
    }

    private volatile Set<String> dismissedCache;

    private Set<String> cachedDismissed() {
        Set<String> c = dismissedCache;
        if (c == null) {
            c = loadDismissed();
            dismissedCache = c;
        }
        return c;
    }

    private Set<String> loadDismissed() {
        File f = new File(plugin.getDataFolder(), DISMISSED_FILE);
        if (!f.isFile()) return new LinkedHashSet<>();
        return new LinkedHashSet<>(YamlConfiguration.loadConfiguration(f).getStringList(DISMISSED_KEY));
    }

    private void saveDismissed(Set<String> ids) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Pack-hosting warnings dismissed with /pm fixup dismiss (plugin:key=value).",
                "Delete a line (or this file) to see the warning again."));
        yaml.set(DISMISSED_KEY, new ArrayList<>(ids));
        yaml.save(new File(plugin.getDataFolder(), DISMISSED_FILE));
    }
}
