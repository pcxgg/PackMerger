package sh.pcx.packmerger.discovery;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;
import sh.pcx.packmerger.api.PackOptions;
import sh.pcx.packmerger.api.RegisteredPack;
import sh.pcx.packmerger.config.ConfigManager;
import sh.pcx.packmerger.remote.FetchResult;
import sh.pcx.packmerger.remote.RemoteSpec;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Bukkit side of {@link ApiPackRegistry}: validates registrations made through
 * {@link sh.pcx.packmerger.api.PackMergerApi}, downloads URL registrations with the
 * remote-pack fetcher, stages everything before each merge and drops the packs of
 * plugins that are disabled.
 */
public class ApiPackManager {

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;
    private volatile ApiPackRegistry registry;
    private volatile PluginPackStager.Report lastReport;

    public ApiPackManager(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        reconfigure();
    }

    /** Applies the stability settings; keeps the registrations. */
    public synchronized void reconfigure() {
        ConfigManager cfg = plugin.getConfigManager();
        ApiPackRegistry fresh = new ApiPackRegistry(new File(plugin.getPacksFolder(), ApiPackRegistry.STAGE_DIR),
                plugin.getMergeEngine() == null ? List.of() : plugin.getMergeEngine().getMergeStrategies(),
                cfg.getPluginPacksStabilityPolls(), cfg.getPluginPacksStabilityIntervalMillis(),
                Thread::sleep, this::download, note -> logger.warning("[api-packs] " + note));
        ApiPackRegistry old = registry;
        if (old != null) {
            for (ApiPackRegistry.Registration r : old.list()) fresh.register(r);
        }
        registry = fresh;
    }

    /**
     * Registers a folder/zip pack.
     *
     * @return {@code true} if the registration is new or changed
     */
    public boolean register(Plugin owner, String alias, Path source, PackOptions options) {
        if (owner == null) throw new IllegalArgumentException("owner must not be null");
        if (source == null) throw new IllegalArgumentException("source must not be null");
        PackOptions o = options == null ? PackOptions.defaults() : options;
        Path abs = source.isAbsolute() ? source
                : plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile().toPath().resolve(source);
        return add(new ApiPackRegistry.Registration(ApiPackRegistry.validateAlias(alias), owner.getName(),
                abs.normalize(), null, o.cluster(), o.allowHttp()));
    }

    /**
     * Registers a downloaded pack.
     *
     * @return {@code true} if the registration is new or changed
     */
    public boolean register(Plugin owner, String alias, URI url, PackOptions options) {
        if (owner == null) throw new IllegalArgumentException("owner must not be null");
        if (url == null || url.getScheme() == null
                || !(url.getScheme().equalsIgnoreCase("https") || url.getScheme().equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("url must be an http(s) URL: " + url);
        }
        PackOptions o = options == null ? PackOptions.defaults() : options;
        return add(new ApiPackRegistry.Registration(ApiPackRegistry.validateAlias(alias), owner.getName(),
                null, url, o.cluster(), o.allowHttp()));
    }

    private boolean add(ApiPackRegistry.Registration r) {
        boolean changed = registry.register(r);
        if (changed) {
            logger.info("[api-packs] " + r.owner() + " registered pack '" + r.alias() + "' ← " + r.sourceText());
            String clash = clashWith(r.alias());
            if (clash != null) {
                logger.warning("[api-packs] alias '" + r.alias() + "' is also used by " + clash
                        + "; the " + clash + " copy is merged in its place. Ask " + r.owner()
                        + " for a different alias, or rename yours.");
            }
        }
        return changed;
    }

    /** @return what else uses {@code alias} as a pack name, or {@code null} */
    private String clashWith(String alias) {
        File packs = plugin.getPacksFolder();
        if (new File(packs, alias).exists()) return "packs/" + alias;
        if (new File(new File(packs, ".remote-cache"), alias + ".zip").exists()) return "remote pack '" + alias + "'";
        if (new File(new File(packs, PluginPackManager.STAGE_DIR), alias + ".zip").exists()) {
            return "plugin pack '" + alias + "'";
        }
        return null;
    }

    /** @return {@code true} if the alias was registered */
    public boolean unregister(String alias) {
        boolean removed = registry.unregister(alias);
        if (removed) logger.info("[api-packs] unregistered pack '" + alias.toLowerCase(Locale.ROOT) + "'");
        return removed;
    }

    /** Drops every pack {@code owner} registered; @return the removed aliases */
    public List<String> unregisterOwner(String owner) {
        List<String> removed = registry.unregisterOwner(owner);
        if (!removed.isEmpty()) {
            logger.info("[api-packs] " + owner + " was disabled; unregistered " + String.join(", ", removed));
        }
        return removed;
    }

    /** @return the registrations as API records */
    public List<RegisteredPack> list() {
        return registry.list().stream()
                .map(r -> new RegisteredPack(r.alias(), r.owner(), r.sourceText(), r.remote()))
                .toList();
    }

    /** Re-download URL registrations on the next merge (startup and /pm reload). */
    public void refreshDownloads() {
        registry.refreshAll();
    }

    /** Stages every registration; run on the merge thread before the merge. */
    public void stageAll() {
        PluginPackStager.Report report = registry.stage(name -> {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            return p != null && p.isEnabled();
        });
        lastReport = report;
        for (PluginPackStager.Outcome o : report.outcomes()) {
            switch (o.status()) {
                case STAGED -> logger.info("[api-packs] staged '" + o.alias() + "' from " + o.pluginName()
                        + " ← " + o.detail());
                case FETCHED -> logger.info("[api-packs] using '" + o.alias() + "' from " + o.pluginName()
                        + " (" + o.detail() + ")");
                case UNCHANGED -> logger.debug("[api-packs] '" + o.alias() + "' unchanged");
                case DEFERRED -> logger.info("[api-packs] '" + o.alias() + "': " + o.detail());
                case NOT_FOUND -> logger.warning("[api-packs] '" + o.alias() + "' from " + o.pluginName()
                        + ": " + o.detail());
                case INVALID -> logger.warning("[api-packs] skipped '" + o.alias() + "' from " + o.pluginName()
                        + ": " + o.detail());
                case FAILED -> logger.warning("[api-packs] failed to stage '" + o.alias() + "' from "
                        + o.pluginName() + ": " + o.detail());
                default -> { }
            }
        }
        for (String alias : report.pruned()) {
            logger.info("[api-packs] removed staged pack '" + alias + "' (unregistered or its plugin is disabled)");
        }
    }

    /** @return API aliases merged on the last pass (empty before the first merge) */
    public List<String> getMergedAliases() {
        PluginPackStager.Report r = lastReport;
        return r == null ? List.of() : r.mergedAliases();
    }

    private PluginPackStager.FetchOutcome download(ApiPackRegistry.Registration r, File dir) {
        if (plugin.getRemotePackManager() == null) {
            return new PluginPackStager.FetchOutcome(false, false, "remote fetcher not ready");
        }
        FetchResult res = plugin.getRemotePackManager().fetchInto(
                new RemoteSpec(r.alias(), r.url().toString(), "on-startup", RemoteSpec.AuthSpec.NONE, r.allowHttp()),
                dir);
        boolean usable = res.status() != FetchResult.Status.ERROR_NO_CACHE
                && res.status() != FetchResult.Status.SKIPPED_BY_POLICY;
        return new PluginPackStager.FetchOutcome(usable, res.status() == FetchResult.Status.FETCHED,
                res.status().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                        + (res.detail() == null ? "" : ": " + res.detail()));
    }
}
