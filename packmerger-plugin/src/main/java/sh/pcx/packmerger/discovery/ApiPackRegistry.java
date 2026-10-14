package sh.pcx.packmerger.discovery;

import sh.pcx.packmerger.merge.strategy.MergeStrategy;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Packs registered by other plugins through
 * {@link sh.pcx.packmerger.api.PackMergerApi#registerPack}. Each registration is
 * staged into {@code packs/.api-packs/<alias>.zip} by a {@link PluginPackStager}, so
 * API packs get the same validation, cluster union, stability window and stale-copy
 * cleanup as discovered plugin packs.
 *
 * <p>Pure (no Bukkit): whether an owner plugin is still enabled and how URLs are
 * downloaded are passed in, so the registry is unit tested with temp folders.</p>
 */
public final class ApiPackRegistry {

    /** Sub-directory of {@code packs/} where API packs are staged. */
    public static final String STAGE_DIR = ".api-packs";

    private static final Pattern ALIAS = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /**
     * One registration.
     *
     * @param alias     validated alias
     * @param owner     owning plugin's name
     * @param source    absolute pack path, or {@code null} for a download
     * @param url       download URL, or {@code null} for a path
     * @param cluster   read {@code source} as a folder of packs
     * @param allowHttp allow a plain-http {@code url}
     */
    public record Registration(String alias, String owner, Path source, URI url,
                               boolean cluster, boolean allowHttp) {
        public boolean remote() { return url != null; }

        /** @return the path or URL as text */
        public String sourceText() { return remote() ? url.toString() : source.toString(); }

        PluginPackAdapter adapter() {
            if (remote()) return PluginPackAdapter.remote(alias, owner, url.toString());
            String path = source.toString();
            return PluginPackAdapter.local(alias, owner,
                    cluster ? PluginPackAdapter.Source.cluster(path) : PluginPackAdapter.Source.pack(path));
        }
    }

    private final PluginPackStager stager;
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private final BiFunction<Registration, File, PluginPackStager.FetchOutcome> downloader;
    /** Remote aliases registered (or re-registered) since the last pass: download them. */
    private final List<String> pendingDownloads = new ArrayList<>();

    /**
     * @param stageDir       {@code packs/.api-packs/}
     * @param strategies     JSON strategies for cluster unions
     * @param polls          stability polls (see {@link PluginPackStager})
     * @param intervalMillis delay between stability polls
     * @param sleeper        sleep implementation
     * @param downloader     downloads a remote registration into {@code dir/<alias>.zip}
     * @param warnings       sink for non-fatal staging notes
     */
    public ApiPackRegistry(File stageDir, List<MergeStrategy> strategies, int polls, long intervalMillis,
                           PluginPackStager.Sleeper sleeper,
                           BiFunction<Registration, File, PluginPackStager.FetchOutcome> downloader,
                           Consumer<String> warnings) {
        this.downloader = downloader;
        // Absolute paths only, so the stager's plugins-folder argument is never used.
        this.stager = new PluginPackStager(stageDir, stageDir, strategies, polls, intervalMillis, sleeper,
                this::fetch, warnings);
    }

    /**
     * @return the alias in its canonical (lower-case) form
     * @throws IllegalArgumentException if it is not {@code [a-z0-9][a-z0-9._-]*} (≤ 64 characters)
     */
    public static String validateAlias(String alias) {
        if (alias == null) throw new IllegalArgumentException("alias must not be null");
        String a = alias.trim().toLowerCase(Locale.ROOT);
        if (!ALIAS.matcher(a).matches()) {
            throw new IllegalArgumentException("invalid pack alias '" + alias
                    + "': use 1-64 characters a-z, 0-9, '.', '_' or '-', starting with a letter or digit");
        }
        return a;
    }

    /**
     * Adds or replaces a registration.
     *
     * @return {@code true} if anything changed (new alias, or a different source/options)
     * @throws IllegalArgumentException if another plugin owns the alias
     */
    public synchronized boolean register(Registration r) {
        Registration existing = registrations.get(r.alias());
        if (existing != null && !existing.owner().equalsIgnoreCase(r.owner())) {
            throw new IllegalArgumentException("pack alias '" + r.alias() + "' is already registered by "
                    + existing.owner());
        }
        if (existing != null && new Registration(r.alias(), existing.owner(), r.source(), r.url(), r.cluster(),
                r.allowHttp()).equals(existing)) {
            return false; // same registration (plugin names compare case-insensitively)
        }
        registrations.put(r.alias(), r);
        if (r.remote() && !pendingDownloads.contains(r.alias())) pendingDownloads.add(r.alias());
        return true;
    }

    /** @return {@code true} if the alias was registered */
    public synchronized boolean unregister(String alias) {
        String a = alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
        pendingDownloads.remove(a);
        return registrations.remove(a) != null;
    }

    /** Removes every registration of {@code owner}; @return the removed aliases */
    public synchronized List<String> unregisterOwner(String owner) {
        List<String> removed = new ArrayList<>();
        registrations.values().removeIf(r -> {
            if (!r.owner().equalsIgnoreCase(owner)) return false;
            removed.add(r.alias());
            return true;
        });
        pendingDownloads.removeAll(removed);
        return removed;
    }

    /** @return a snapshot of the registrations, in registration order */
    public synchronized List<Registration> list() {
        return List.copyOf(registrations.values());
    }

    /** Re-download every remote registration on the next pass (startup and /pm reload). */
    public synchronized void refreshAll() {
        for (Registration r : registrations.values()) {
            if (r.remote() && !pendingDownloads.contains(r.alias())) pendingDownloads.add(r.alias());
        }
    }

    /**
     * Stages every registration whose owner is active and deletes staged copies of
     * everything else. Run before each merge, on the merge thread.
     *
     * @param ownerActive whether a plugin (by name) is still enabled
     */
    public PluginPackStager.Report stage(Predicate<String> ownerActive) {
        List<Registration> snapshot;
        boolean refresh;
        synchronized (this) {
            snapshot = List.copyOf(registrations.values());
            refresh = !pendingDownloads.isEmpty();
            pendingDownloads.clear();
        }
        List<PluginPackStager.Entry> entries = new ArrayList<>();
        for (Registration r : snapshot) {
            entries.add(new PluginPackStager.Entry(r.adapter(), true, ownerActive.test(r.owner())));
        }
        return stager.run(entries, refresh);
    }

    private PluginPackStager.FetchOutcome fetch(PluginPackAdapter adapter, File dir) {
        Registration r;
        synchronized (this) {
            r = registrations.get(adapter.alias());
        }
        if (r == null || downloader == null) {
            return new PluginPackStager.FetchOutcome(false, false, "not registered");
        }
        return downloader.apply(r, dir);
    }
}
