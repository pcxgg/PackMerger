package sh.pcx.packmerger;

import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import sh.pcx.packmerger.api.PackMergerApi;
import sh.pcx.packmerger.api.PackOptions;
import sh.pcx.packmerger.api.RegisteredPack;
import sh.pcx.packmerger.api.events.PackMergerCollectPacksEvent;
import sh.pcx.packmerger.api.events.PackBedrockConvertedEvent;
import sh.pcx.packmerger.api.events.PackMergeStartedEvent;
import sh.pcx.packmerger.api.events.PackMergedEvent;
import sh.pcx.packmerger.api.events.PackUploadFailedEvent;
import sh.pcx.packmerger.api.events.PackUploadedEvent;
import sh.pcx.packmerger.api.events.PackValidationFailedEvent;
import sh.pcx.packmerger.commands.PackMergerCommand;
import sh.pcx.packmerger.bedrock.BedrockConversionResult;
import sh.pcx.packmerger.bedrock.BedrockConverter;
import sh.pcx.packmerger.bedrock.BedrockConverterOptions;
import sh.pcx.packmerger.config.ConfigManager;
import sh.pcx.packmerger.config.MessageManager;
import sh.pcx.packmerger.distribution.PackDistributor;
import sh.pcx.packmerger.distribution.PackIdentity;
import sh.pcx.packmerger.distribution.ProxyLink;
import sh.pcx.packmerger.distribution.PublishedPack;
import sh.pcx.packmerger.discovery.ApiPackManager;
import sh.pcx.packmerger.integration.CompetingHostManager;
import sh.pcx.packmerger.integration.GeyserFolder;
import sh.pcx.packmerger.merge.OutputCopier;
import sh.pcx.packmerger.discovery.PluginPackManager;
import sh.pcx.packmerger.listeners.PluginDisableListener;
import sh.pcx.packmerger.listeners.PlayerJoinListener;
import sh.pcx.packmerger.merge.FileWatcher;
import sh.pcx.packmerger.merge.MergeProvenance;
import sh.pcx.packmerger.merge.PackMergeEngine;
import sh.pcx.packmerger.merge.PackValidator;
import sh.pcx.packmerger.remote.FetchResult;
import sh.pcx.packmerger.remote.RemotePackManager;
import sh.pcx.packmerger.update.UpdateChecker;
import sh.pcx.packmerger.upload.PolymathUploadProvider;
import sh.pcx.packmerger.upload.S3UploadProvider;
import sh.pcx.packmerger.upload.SelfHostProvider;
import sh.pcx.packmerger.upload.UploadChain;
import sh.pcx.packmerger.upload.UploadProvider;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Main plugin class for PackMerger — a Paper plugin that merges multiple Minecraft resource
 * packs into a single pack and distributes it to players.
 *
 * <p>This class acts as the central coordinator for the plugin's lifecycle. On enable, it
 * initializes all subsystems in order: configuration, directory structure, merge engine,
 * validator, player cache, distributor, upload provider, commands, listeners, and the
 * file watcher. On disable, it tears them down in reverse.</p>
 *
 * <p>The core workflow is: merge packs &rarr; validate &rarr; compute SHA-1 hash &rarr;
 * upload via the configured provider &rarr; distribute to online players. This entire
 * pipeline is orchestrated by {@link #mergeAndUpload(org.bukkit.command.CommandSender)}.</p>
 *
 * @see PackMergeEngine
 * @see PackDistributor
 * @see UploadProvider
 * @see ConfigManager
 */
public class PackMergerBootstrap implements PackMergerApi {

    /**
     * The JavaPlugin entry point (typically {@link sh.pcx.packmerger.loader.PackMergerLoader})
     * that the server registered. All Bukkit-API access is routed through this
     * reference because the Bootstrap itself is a plain class living inside the
     * loader's isolated classloader.
     */
    private JavaPlugin loader;

    /** Manages loading and access to all plugin configuration values. */
    private ConfigManager configManager;

    /** Manages player-facing messages loaded from messages_en.yml. */
    private MessageManager messageManager;

    /** Console logger with colored category tags and configurable log levels. */
    private PluginLogger logger;

    /** Performs the actual merge of resource packs from the packs folder. */
    private PackMergeEngine mergeEngine;

    /** Validates merged pack output for structural correctness. */
    private PackValidator validator;

    /** Watches the packs folder for file changes to trigger hot-reload merges. */
    private FileWatcher fileWatcher;

    /** The active upload provider (Polymath or self-host) used to serve the merged pack. */
    private UploadProvider uploadProvider;

    /** Name of {@link #uploadProvider} ("self-host", "s3", "polymath"), after any init fallback. */
    private volatile String uploadProviderName = "self-host";

    /** Providers from {@code upload.fallback}, created on first use; cleared on re-init. */
    private final Map<String, UploadProvider> fallbackProviders = new ConcurrentHashMap<>();

    /** How the live pack was published (provider, fallback notes); {@code null} before the first publish. */
    private volatile UploadChain.Outcome lastUploadOutcome;

    /** Handles sending the resource pack to individual players or all online players. */
    private PackDistributor distributor;

    /** Link to the PackMerger proxy plugin (relay + Bungee offers). */
    private volatile ProxyLink proxyLink;

    /** Fetches remote pack sources into {@code packs/.remote-cache/}. */
    private RemotePackManager remotePackManager;

    /** Stages packs generated by other installed plugins into {@code packs/.plugin-packs/}. */
    private PluginPackManager pluginPackManager;

    /** Checks for newer PackMerger releases on enable. */
    private UpdateChecker updateChecker;

    /** Packs registered by other plugins through the API, staged into {@code packs/.api-packs/}. */
    private ApiPackManager apiPackManager;

    /** Detects ItemsAdder/Oraxen/Nexo hosting their own pack; backs {@code /pm fixup}. */
    private CompetingHostManager competingHostManager;

    /** Result of the last upload attempt (merge or re-publish), for /pm status. */
    private volatile UploadAttempt lastUploadAttempt;

    /** Result of the last Bedrock conversion, for /pm status. */
    private volatile BedrockState lastBedrock;

    /**
     * The last upload attempt.
     *
     * @param at     when it finished
     * @param ok     whether it published a pack
     * @param detail the URL, or the error
     */
    public record UploadAttempt(Instant at, boolean ok, String detail) {}

    /**
     * The last Bedrock conversion.
     *
     * @param at             when it ran
     * @param items          items converted
     * @param manifestVersion the .mcpack manifest version (changes with the content)
     * @param deployedTo     Geyser folder it was copied into, or {@code null}
     * @param detail         a note (no items, Geyser folder missing, error)
     */
    public record BedrockState(Instant at, int items, String manifestVersion, String deployedTo, String detail) {}

    /** {@code true} until the delayed startup merge starts: it picks up early API registrations. */
    private final MergeRequestState mergeState = new MergeRequestState();

    /** A merge requested (API registration, plugin disabled) while one was running or queued. */
    
    /** A coalesced {@link #requestMerge} is scheduled. */
    private final AtomicBoolean mergeScheduled = new AtomicBoolean(false);

    /**
     * The pack currently offered to players: an immutable (url, sha1, uuid) record that
     * is only replaced, as a whole, after an upload succeeded. {@code null} until the
     * first publish (re-publish of the existing output on enable, or the first merge).
     */
    private final AtomicReference<PublishedPack> publishedPack = new AtomicReference<>();

    /** Serialises publishing (merge uploads and re-publishes of the existing output). */
    private final ReentrantLock publishLock = new ReentrantLock();

    /**
     * Incremented each time the upload provider is (re)initialised. A published record
     * from an older generation must be re-published through the current provider.
     */
    private volatile int providerGeneration;

    /** Timestamp of the last successful merge, displayed in the status command. */
    private LocalDateTime lastMergeTime;

    /**
     * Guard flag that prevents concurrent merges. Uses {@link AtomicBoolean} so that
     * the file watcher thread and command thread cannot trigger overlapping merges.
     */
    private final AtomicBoolean merging = new AtomicBoolean(false);

    /** Invoked by the loader during its {@code onLoad()}. Captures the JavaPlugin reference. */
    public void onLoad(JavaPlugin loader) {
        this.loader = loader;
    }

    public void onEnable(JavaPlugin loader) {
        this.loader = loader;
        // Initialize config first — all other components depend on it
        configManager = new ConfigManager(this);
        configManager.load();

        // Initialize the colored console logger after config is loaded
        logger = new PluginLogger(this, configManager.getLogLevel());

        // Initialize player-facing messages
        messageManager = new MessageManager(this);
        messageManager.load();

        // Ensure the plugin's working directories exist before any component tries to use them
        getPacksFolder().mkdirs();
        getOutputFolder().mkdirs();
        getCacheFolder().mkdirs();

        // Initialize components in dependency order
        mergeEngine = new PackMergeEngine(this);
        validator = new PackValidator(this);
        // Surface pack-format registry coverage vs. the running server so an
        // operator can spot when the server is newer than the registry knows
        // (which silently disables the pack_format drift check).
        logger.debug(sh.pcx.packmerger.merge.PackFormatRegistry.describe()
                + "; server reports Minecraft " + Bukkit.getMinecraftVersion());
        distributor = new PackDistributor(this);
        remotePackManager = new RemotePackManager(this);
        pluginPackManager = new PluginPackManager(this);
        pluginPackManager.reconfigure();
        apiPackManager = new ApiPackManager(this);
        competingHostManager = new CompetingHostManager(this);
        updateChecker = new UpdateChecker(this);

        // Poll versions.json off the main thread so a slow GitHub response
        // doesn't delay enable. Failures are logged at DEBUG and silently
        // dropped — update-check is informational, never load-bearing.
        if (configManager.isUpdateCheckEnabled()) {
            CompletableFuture.runAsync(() ->
                    updateChecker.checkNow(configManager.getUpdateCheckUrl(),
                            loader.getPluginMeta().getVersion()));
        }

        // Fetch on-startup remote packs before the first merge so they're
        // available from discovery. Runs async so onEnable doesn't stall on
        // network I/O — the auto-merge below waits on the fetch future.
        CompletableFuture<Void> remoteFetch = CompletableFuture.runAsync(() -> {
            if (configManager.getRemotePacks().isEmpty()) return;
            List<FetchResult> results = remotePackManager.fetchAll(
                    configManager.getRemotePacks(), RemotePackManager.Trigger.STARTUP);
            logRemoteFetchResults(results);
        });

        // Restore last merge provenance from disk if present so /pm inspect and
        // the plugin API have data immediately on startup, before any merge runs
        loadLastProvenance();

        // Initialize upload provider (may start an HTTP server if using self-host)
        initUploadProvider();

        // Re-publish the merged pack from the previous run right away, so joining
        // players get a pack immediately instead of waiting for the delayed merge (and
        // so a first merge that fails validation really leaves the previous pack live).
        republishExistingAsync("startup");

        // Register Brigadier commands via Paper's lifecycle API
        new PackMergerCommand(this);

        // Register event listeners for player join and resource pack status tracking
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(this), loader);
        // Drop API-registered packs of plugins that are disabled.
        Bukkit.getPluginManager().registerEvents(new PluginDisableListener(this), loader);

        // Publish the API for other plugins (PackMergerApi.get()).
        Bukkit.getServicesManager().register(PackMergerApi.class, this, loader, ServicePriority.Normal);

        // Link to the PackMerger proxy plugin (Velocity/BungeeCord): relays published packs
        // (distribution.proxy-notify) and honours signed pack offers from a Bungee proxy.
        proxyLink = new ProxyLink(this);
        proxyLink.register();

        // bStats metrics (https://bstats.org/plugin/bukkit/PackMerger/32086).
        // Guarded so a scheduler incompatibility (e.g. on Folia) can't fail enable.
        try {
            new org.bstats.bukkit.Metrics(loader, 32086);
        } catch (Throwable t) {
            logger.debug("bStats metrics not started: " + t.getMessage());
        }

        logger.info("PackMerger enabled!");

        // Kick off the initial merge if configured to do so, but wait for the
        // remote-pack fetch to finish so the first merge sees the cached zips.
        // Delay the first merge so other pack-generating plugins (Oraxen, Nexo,
        // …) have finished enabling + writing their packs before we stage them.
        if (configManager.isAutoMergeOnStartup()) {
            mergeState.startupScheduled();
            long delaySec = configManager.getPluginPacksMergeDelaySeconds();
            // whenComplete, not thenRun: a failed remote fetch must not cancel the startup
            // merge (API registrations made while plugins enable wait for it).
            remoteFetch.whenComplete((ignored, fetchError) -> {
                if (fetchError != null) {
                    logger.warning("Remote pack fetch on startup failed: " + fetchError.getMessage());
                }
                if (delaySec <= 0) {
                    mergeAndUpload(null);
                } else {
                    Bukkit.getAsyncScheduler().runDelayed(loader,
                            t -> mergeAndUpload(null), delaySec, TimeUnit.SECONDS);
                }
            });
        }

        // Check whether ItemsAdder/Oraxen/Nexo host their own pack, once every plugin
        // has enabled (the first tick runs after startup) and off the main thread.
        Bukkit.getGlobalRegionScheduler().run(loader, t ->
                Bukkit.getAsyncScheduler().runNow(loader, a -> competingHostManager.scan()));

        // Start watching for file changes in the packs folder (hot-reload)
        startFileWatcher();

        // Poll the resolved plugin pack sources so a plugin rebuilding its pack
        // (e.g. /oraxen reload) triggers a merge on its own. The watcher ignores
        // sources until the first staging pass has seen them.
        pluginPackManager.startWatcher();
    }

    public void onDisable(JavaPlugin loader) {
        // Stop the file watchers first to prevent new merges from starting
        stopFileWatcher();
        if (pluginPackManager != null) {
            pluginPackManager.stopWatcher();
        }

        // Shut down the built-in HTTP server if self-host is the active provider (or a fallback)
        if (uploadProvider instanceof SelfHostProvider selfHost) {
            selfHost.stop();
        }
        stopFallbackProviders();

        // Cancel any outstanding async tasks (the global-region and entity
        // schedulers auto-cancel on plugin disable, so they don't need
        // explicit cleanup). BukkitScheduler.cancelTasks is deliberately
        // avoided here — it throws UnsupportedOperationException on Folia.
        Bukkit.getAsyncScheduler().cancelTasks(loader);

        logger.info("PackMerger disabled!");
    }

    /**
     * Initializes (or re-initializes) the upload provider based on the current configuration.
     *
     * <p>If a {@link SelfHostProvider} was previously active, its HTTP server is stopped before
     * switching. If the configured provider name is unrecognized, falls back to self-host with
     * a console warning.</p>
     *
     * @see UploadProvider
     * @see PolymathUploadProvider
     * @see SelfHostProvider
     */
    public void initUploadProvider() {
        String provider = configManager.getUploadProvider();
        // Fallback providers are rebuilt from the (possibly changed) config on first use.
        // Stopped first, so a fallback self-host releases its port before a primary binds it.
        stopFallbackProviders();

        // Keep a running self-host server when it stays on the same port, so the URL
        // that is already advertised keeps answering through /pm reload.
        if (uploadProvider instanceof SelfHostProvider selfHost
                && "self-host".equalsIgnoreCase(provider) && selfHost.reconfigure()) {
            providerGeneration++;
            uploadProviderName = "self-host";
            logger.upload("Self-host HTTP server kept running on port " + configManager.getSelfHostPort());
            return;
        }

        // Stop existing self-host HTTP server if we're switching providers (or ports)
        if (uploadProvider instanceof SelfHostProvider selfHost) {
            selfHost.stop();
        }

        providerGeneration++;
        uploadProvider = switch (provider.toLowerCase(Locale.ROOT)) {
            case "polymath" -> new PolymathUploadProvider(this);
            case "s3" -> {
                try {
                    yield new S3UploadProvider(this);
                } catch (Exception e) {
                    logger.error("S3 upload provider failed to initialize: " + e.getMessage()
                            + ". Falling back to self-host.");
                    SelfHostProvider selfHost = new SelfHostProvider(this);
                    selfHost.start();
                    yield selfHost;
                }
            }
            case "self-host" -> {
                SelfHostProvider selfHost = new SelfHostProvider(this);
                selfHost.start();
                yield selfHost;
            }
            default -> {
                logger.warning("Unknown upload provider: " + provider + ", defaulting to self-host");
                SelfHostProvider selfHost = new SelfHostProvider(this);
                selfHost.start();
                yield selfHost;
            }
        };
        uploadProviderName = nameOf(uploadProvider);
    }

    private static String nameOf(UploadProvider provider) {
        if (provider instanceof SelfHostProvider) return "self-host";
        if (provider instanceof S3UploadProvider) return "s3";
        if (provider instanceof PolymathUploadProvider) return "polymath";
        return provider.getClass().getSimpleName();
    }

    /** Stops and forgets the fallback providers (their HTTP server, for a self-host fallback). */
    private void stopFallbackProviders() {
        for (UploadProvider p : fallbackProviders.values()) {
            if (p instanceof SelfHostProvider selfHost) selfHost.stop();
        }
        fallbackProviders.clear();
    }

    /**
     * Returns the provider for a name in the upload chain: the active provider for its own
     * name, otherwise a fallback created on first use.
     *
     * @return the provider, or {@code null} if it is not configured (so the chain skips it)
     */
    private UploadProvider providerFor(String name) {
        if (name.equals(uploadProviderName)) return uploadProvider;
        UploadProvider existing = fallbackProviders.get(name);
        if (existing != null) return existing;
        int generation = providerGeneration;
        UploadProvider created = switch (name) {
            case "s3" -> {
                try {
                    yield new S3UploadProvider(this);
                } catch (Exception e) {
                    logger.debug("Fallback provider s3 is not configured: " + e.getMessage());
                    yield null;
                }
            }
            case "polymath" -> configManager.getPolymathServer().isBlank() ? null : new PolymathUploadProvider(this);
            case "self-host" -> {
                SelfHostProvider selfHost = new SelfHostProvider(this);
                selfHost.start();
                yield selfHost.isRunning() ? selfHost : null;
            }
            default -> {
                logger.warning("Unknown provider '" + name + "' in upload.fallback (valid: self-host, s3, polymath)");
                yield null;
            }
        };
        if (created == null) return null;
        if (generation != providerGeneration) {
            // /pm reload re-initialised the providers meanwhile: don't leak this one.
            if (created instanceof SelfHostProvider selfHost) selfHost.stop();
            return null;
        }
        fallbackProviders.put(name, created);
        return created;
    }

    /** Wraps a provider as a chain step; self-host adds its reachability verification. */
    private static UploadChain.Step stepFor(UploadProvider provider) {
        if (provider == null) return null;
        if (provider instanceof UploadChain.Step step) return step;
        return provider::upload;
    }

    /**
     * Executes the full merge-validate-upload pipeline asynchronously.
     *
     * <p>Only one merge can run at a time — if a merge is already in progress, the caller
     * is notified and the request is ignored. The merge work runs off the main thread via
     * {@link CompletableFuture#runAsync}. Player-facing messages go through Adventure
     * (which is thread-safe), and {@link PackDistributor#sendPack} self-schedules on
     * each player's region thread — so no explicit main-thread dispatch is needed here.
     * This keeps the method Folia-compatible.</p>
     *
     * <p>Pipeline steps:</p>
     * <ol>
     *   <li>Merge all packs via {@link PackMergeEngine#merge()}</li>
     *   <li>Validate the output via {@link PackValidator#validate(File)}</li>
     *   <li>Compute SHA-1 hash for Minecraft's resource pack protocol</li>
     *   <li>Upload via the configured {@link UploadProvider} (if auto-upload is enabled) and,
     *       only if that succeeds, publish the new (url, sha1, uuid) record</li>
     *   <li>Send the pack to players who joined before one existed and, if the content
     *       changed, apply {@code on-new-pack} ({@link PackDistributor#onPublished(boolean)})</li>
     * </ol>
     *
     * @param sender the command sender to receive status messages, or {@code null} for automated merges
     *               (e.g. startup, hot-reload) where no feedback is needed
     * @return a future that completes when the entire pipeline finishes (including upload)
     */
    public CompletableFuture<Void> mergeAndUpload(org.bukkit.command.CommandSender sender) {
        // Atomically claim the merge lock — prevents concurrent merges from file watcher + commands
        if (!merging.compareAndSet(false, true)) {
            if (sender != null) {
                sender.sendMessage(messageManager.getMessage("merge.already-running"));
            }
            // The running merge covers the startup merge too; make sure API requests are honoured again
            // and re-run once it ends (picks up anything registered meanwhile).
            mergeState.mergeBlockedByRunning();
            return CompletableFuture.completedFuture(null);
        }

        if (sender != null) {
            sender.sendMessage(messageManager.getMessage("merge.starting"));
        }

        mergeState.mergeStarted();

        // Run the entire pipeline off the main thread to avoid blocking the server tick loop
        return CompletableFuture.runAsync(() -> {
            try {
                // Step 1: Merge all packs into a temp output zip (.new.zip) so we can
                // validate before committing. This is the rollback-on-validation-failure
                // escape hatch — if validation trips a critical error we leave the
                // previous output intact and players keep seeing yesterday's pack.
                File finalOutputFile = getOutputFile();
                File tempOutputFile = new File(finalOutputFile.getParentFile(),
                        finalOutputFile.getName() + ".new");

                // Stage packs generated by other installed plugins (Oraxen, Nexo, …)
                // into packs/.plugin-packs/ so the merge discovers them by alias.
                // Re-run on every merge so fresh plugin output is always captured.
                if (pluginPackManager != null) {
                    pluginPackManager.stageAll();
                }
                // Let plugins contribute packs lazily, then stage every API-registered pack
                // into packs/.api-packs/ (and drop the ones no longer registered).
                if (apiPackManager != null) {
                    Bukkit.getPluginManager().callEvent(new PackMergerCollectPacksEvent(this));
                    apiPackManager.stageAll();
                }
                // Re-check other item plugins' hosting settings (they may have changed them).
                if (competingHostManager != null) {
                    competingHostManager.scan();
                }
                // Drop cached downloads of remote packs that are no longer declared,
                // so removing a remote-packs entry actually removes the pack.
                if (remotePackManager != null) {
                    remotePackManager.pruneUndeclared(configManager.getRemotePacks());
                }

                File mergedTemp = mergeEngine.merge(tempOutputFile);
                if (mergedTemp == null) {
                    if (sender != null) {
                        sender.sendMessage(messageManager.getMessage("merge.failed-no-packs"));
                    }
                    return;
                }

                lastMergeTime = LocalDateTime.now();

                // Fire PackMergeStartedEvent with the resolved pack order now that we
                // know the merge produced output.
                MergeProvenance provenance = mergeEngine.getLastProvenance();
                if (provenance != null) {
                    Bukkit.getPluginManager().callEvent(new PackMergeStartedEvent(provenance.packOrder()));
                }

                // Step 2: Validate the merged pack for structural issues
                PackValidator.ValidationResult validationResult = validator.validate(mergedTemp);

                // Step 2b: Decide whether this output is shippable. If validation
                // tripped errors (or warnings, when fail-on-warnings is enabled) and
                // rollback is enabled AND a previous output exists to fall back to,
                // we delete the temp and fire PackValidationFailedEvent with rolledBack=true.
                boolean hasValidationFailure = validationResult.errors() > 0
                        || (configManager.isFailOnWarnings() && validationResult.warnings() > 0);
                boolean canRollback = configManager.isRollbackOnErrors() && finalOutputFile.exists();

                if (hasValidationFailure && canRollback) {
                    logger.error("Validation produced " + validationResult.errors() + " error(s) and "
                            + validationResult.warnings() + " warning(s). Rolling back — the previous merged pack remains live.");
                    // Delete the temp zip + its provenance sidecar. Restore lastProvenance from the old output's sidecar.
                    safeDelete(tempOutputFile);
                    safeDelete(PackMergeEngine.provenanceSidecar(tempOutputFile));
                    restoreProvenanceFrom(finalOutputFile);
                    Bukkit.getPluginManager().callEvent(new PackValidationFailedEvent(validationResult, true));
                    if (sender != null) {
                        sender.sendMessage(messageManager.getMessage("merge.failed",
                                "error", "validation failed; previous pack preserved"));
                    }
                    return;
                }

                if (hasValidationFailure) {
                    // Either rollback is disabled or there's no previous pack — ship the broken pack,
                    // but fire the event so listeners can page someone.
                    logger.error("Validation produced " + validationResult.errors() + " error(s); shipping anyway ("
                            + (configManager.isRollbackOnErrors() ? "no previous pack to roll back to" : "rollback disabled")
                            + ")");
                    Bukkit.getPluginManager().callEvent(new PackValidationFailedEvent(validationResult, false));
                }

                // Step 2c: Commit — promote the temp zip + its provenance sidecar to the final paths.
                File outputFile;
                try {
                    outputFile = commitMerge(tempOutputFile, finalOutputFile);
                } catch (IOException ioe) {
                    logger.error("Failed to commit merged pack (" + ioe.getMessage() + ")", ioe);
                    safeDelete(tempOutputFile);
                    safeDelete(PackMergeEngine.provenanceSidecar(tempOutputFile));
                    if (sender != null) {
                        sender.sendMessage(messageManager.getMessage("merge.failed",
                                        "error", ioe.getMessage()));
                    }
                    return;
                }

                // Step 3: Compute SHA-1 hash (required by Minecraft's resource pack protocol)
                byte[] hash = computeSha1(outputFile);
                String hashHex = bytesToHex(hash);
                PublishedPack live = publishedPack.get();
                if (live != null && live.sha1Hex().equals(hashHex)) {
                    logger.info("Merged pack SHA1: " + hashHex + " (unchanged)");
                } else {
                    logger.info("Merged pack SHA1: " + hashHex);
                }

                // Fire PackMergedEvent before upload so listeners can inspect the
                // output and validation result before it's shipped.
                Bukkit.getPluginManager().callEvent(new PackMergedEvent(outputFile, hash, provenance, validationResult));

                // Copy the merged zip to merge.output-copy-to (serve it with something else).
                copyOutput(outputFile);

                // Convert to a Bedrock pack for Geyser players (opt-in; off by default).
                convertBedrockIfEnabled(outputFile);

                // Step 4: Upload, then publish the (url, sha1, uuid) record in one step.
                // Until the upload succeeds, players keep getting the previous record.
                if (configManager.isAutoUpload()) {
                    try {
                        PublishedPack record = publish(outputFile, hash, false);
                        lastUploadAttempt = new UploadAttempt(Instant.now(), true, record.url());
                        if (sender != null) {
                            sender.sendMessage(messageManager.getMessage("merge.upload-complete",
                                            "url", record.url()));
                        }
                    } catch (Exception e) {
                        logger.error("Upload failed; players keep getting the previously published pack"
                                + (publishedPack.get() == null ? " (none yet)" : ""), e);
                        lastUploadAttempt = new UploadAttempt(Instant.now(), false, String.valueOf(e.getMessage()));
                        Bukkit.getPluginManager().callEvent(new PackUploadFailedEvent(e));
                        if (sender != null) {
                            sender.sendMessage(messageManager.getMessage("merge.upload-failed",
                                            "error", e.getMessage()));
                        }
                    }
                } else {
                    if (sender != null) {
                        sender.sendMessage(messageManager.getMessage("merge.complete-no-upload"));
                    }
                }
            } catch (Exception e) {
                logger.error("Merge failed", e);
                if (sender != null) {
                    sender.sendMessage(messageManager.getMessage("merge.failed",
                                    "error", e.getMessage()));
                }
            } finally {
                // Always release the merge lock, even on failure
                merging.set(false);
                // A registration that arrived while this merge ran gets its own merge.
                if (mergeState.takeDeferred()) {
                    requestMerge("a change requested during the previous merge");
                }
            }
        });
    }

    /**
     * Schedules a merge for an API change (pack registered/unregistered, owner disabled).
     * Requests within a second are coalesced into one merge; a request made while a merge
     * runs is honoured once it finishes; requests before the startup merge are left to it.
     */
    public void requestMerge(String reason) {
        if (mergeState.isStartupPending() || Bukkit.isStopping() || !loader.isEnabled()) return;
        if (!mergeScheduled.compareAndSet(false, true)) return;
        logger.debug("Merge requested: " + reason);
        Bukkit.getAsyncScheduler().runDelayed(loader, t -> {
            mergeScheduled.set(false);
            if (merging.get()) {
                mergeState.deferRequest();
            } else {
                mergeAndUpload(null);
            }
        }, 1, TimeUnit.SECONDS);
    }

    /**
     * Uploads {@code file} through the current provider and, only if that succeeds,
     * swaps in a new {@link PublishedPack}. Fires {@link PackUploadedEvent}, relays the
     * pack to the proxy when it changed, and lets the distributor flush players who were
     * waiting for a pack and apply {@code on-new-pack}.
     *
     * @param republish {@code true} when re-publishing an existing output (skipped if a
     *                  publish through the current provider already happened)
     * @return the record now live, or {@code null} if a republish was skipped
     * @throws Exception if the upload failed; the previous record stays live
     */
    private PublishedPack publish(File file, byte[] hash, boolean republish) throws Exception {
        publishLock.lock();
        try {
            int generation = providerGeneration;
            PublishedPack previous = publishedPack.get();
            if (republish && previous != null && previous.providerGeneration() == generation) {
                return null; // a merge already published through this provider
            }
            String hashHex = bytesToHex(hash);
            List<String> chain = UploadChain.order(uploadProviderName, configManager.getUploadFallback());
            UploadChain.Outcome outcome = UploadChain.run(chain, name -> stepFor(providerFor(name)), file, hashHex);
            lastUploadOutcome = outcome;
            String url = outcome.url();
            SelfHostProvider selfHost = getSelfHostProvider();
            boolean verificationFailed = selfHost != null
                    && selfHost.getVerification().state() == SelfHostProvider.VerificationState.FAILED;
            if (outcome.fallback() || (outcome.degraded() && verificationFailed)) {
                // A non-routable self-host URL alone is already warned about by the provider
                // (and shown to admins on join); a failed check or a fallback is loud.
                String problem = "Pack hosting problem: published through " + outcome.describe();
                logger.warning(problem);
                alertAdmins("[PackMerger] " + problem + " (see /pm status)");
            }
            PublishedPack record = new PublishedPack(url, hash,
                    PackIdentity.forServer(configManager.getServerName()), Instant.now(), generation);
            publishedPack.set(record);

            boolean isNewPack = !record.sameContentAs(previous);
            logger.upload((republish ? "Pack re-published: " : "Pack uploaded successfully: ") + url);
            Bukkit.getPluginManager().callEvent(new PackUploadedEvent(url, hash));
            // Relay to the proxy (distribution.proxy-notify); ProxyLink only sends a pack
            // the proxy hasn't had yet, and waits for a player if nobody is online.
            if (proxyLink != null) proxyLink.onPublished(record);
            // PackDistributor self-schedules on each player's region: safe off-thread.
            distributor.onPublished(isNewPack);
            return record;
        } finally {
            publishLock.unlock();
        }
    }

    /**
     * Re-publishes the merged pack already on disk ({@code output/}) through the
     * current upload provider, off the main thread. Used on enable and after
     * {@code /pm reload} so a pack is available immediately and stays available while
     * the next merge runs; a later merge that fails validation then really leaves the
     * previous pack live.
     */
    public CompletableFuture<Void> republishExistingAsync(String reason) {
        if (!configManager.isAutoUpload()) return CompletableFuture.completedFuture(null);
        File output = getOutputFile();
        return CompletableFuture.runAsync(() -> {
            if (!output.isFile()) {
                logger.debug("No previous merged pack to re-publish (" + reason + ")");
                return;
            }
            File stable = null;
            try {
                // Work on a private copy: a concurrent merge may replace output/ between
                // hashing and uploading, which would pair this hash with different bytes.
                stable = File.createTempFile("republish-", ".zip", output.getParentFile());
                java.nio.file.Files.copy(output.toPath(), stable.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                byte[] hash = computeSha1(stable);
                PublishedPack record = publish(stable, hash, true);
                if (record != null) {
                    lastUploadAttempt = new UploadAttempt(Instant.now(), true, record.url());
                    logger.info("Re-published the existing merged pack on " + reason + " (SHA1: "
                            + record.sha1Hex() + ")");
                }
            } catch (Exception e) {
                lastUploadAttempt = new UploadAttempt(Instant.now(), false, String.valueOf(e.getMessage()));
                logger.error("Could not re-publish the existing merged pack on " + reason + ": "
                        + e.getMessage(), e);
                Bukkit.getPluginManager().callEvent(new PackUploadFailedEvent(e));
            } finally {
                if (stable != null) stable.delete();
            }
        });
    }

    /**
     * Starts the file watcher for hot-reload functionality.
     *
     * <p>If hot-reload is disabled in config, this method is a no-op. If a watcher is
     * already running, it is stopped before a new one is started.</p>
     *
     * @see FileWatcher
     */
    public void startFileWatcher() {
        if (!configManager.isHotReloadEnabled()) return;
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
        fileWatcher = new FileWatcher(this);
        fileWatcher.start();
    }

    /**
     * Stops the file watcher if one is currently active.
     *
     * @see FileWatcher
     */
    public void stopFileWatcher() {
        if (fileWatcher != null) {
            fileWatcher.stop();
            fileWatcher = null;
        }
    }

    /**
     * Performs a full reload: re-reads the config, re-initializes the upload provider,
     * and restarts the file watcher. Called by the {@code /packmerger reload} command.
     *
     * <p>Also re-fetches any remote packs whose {@code refresh} policy is
     * {@code on-reload} (or {@code on-startup}) so a reload picks up origin
     * updates without a full plugin restart.</p>
     *
     * <p>Does not automatically trigger a merge — the calling command handles that separately.</p>
     */
    public void reload() {
        configManager.load();
        messageManager.load();
        logger.setLevel(configManager.getLogLevel());
        if (distributor != null) distributor.reloadSettings();
        if (proxyLink != null) proxyLink.reloadSettings();
        initUploadProvider();
        // Re-publish the existing output through the (re)initialised provider before the
        // re-merge, so the advertised URL keeps serving the current pack meanwhile.
        republishExistingAsync("reload");
        stopFileWatcher();
        startFileWatcher();
        if (pluginPackManager != null) {
            pluginPackManager.reconfigure();
            pluginPackManager.startWatcher();
        }
        if (apiPackManager != null) {
            apiPackManager.reconfigure();
            apiPackManager.refreshDownloads();
        }

        if (competingHostManager != null) {
            Bukkit.getAsyncScheduler().runNow(loader, a -> competingHostManager.scan());
        }

        if (remotePackManager != null && !configManager.getRemotePacks().isEmpty()) {
            List<FetchResult> results = remotePackManager.fetchAll(
                    configManager.getRemotePacks(), RemotePackManager.Trigger.RELOAD);
            logRemoteFetchResults(results);
        }
    }

    /** @return the remote pack manager, or {@code null} before onEnable completes */
    public RemotePackManager getRemotePackManager() { return remotePackManager; }

    /** @return the plugin-pack manager (auto-discovery), or {@code null} before onEnable completes */
    public PluginPackManager getPluginPackManager() { return pluginPackManager; }

    /** @return the API pack manager, or {@code null} before onEnable completes */
    public ApiPackManager getApiPackManager() { return apiPackManager; }

    /** @return the competing-host check, or {@code null} before onEnable completes */
    public CompetingHostManager getCompetingHostManager() { return competingHostManager; }

    /** @return the last upload attempt, or {@code null} if none happened yet */
    public UploadAttempt getLastUploadAttempt() { return lastUploadAttempt; }

    /** @return the last Bedrock conversion, or {@code null} if none ran */
    public BedrockState getLastBedrock() { return lastBedrock; }

    /**
     * Switches verbose (debug) logging and persists it as {@code log-level} in config.yml:
     * {@code debug} when on, {@code info} when off.
     */
    public void setVerbose(boolean on) {
        // Start from the file on disk so hand edits not yet applied with /pm reload survive.
        reloadConfig();
        getConfig().set("log-level", on ? "debug" : "info");
        saveConfig();
        configManager.load();
        logger.setLevel(configManager.getLogLevel());
    }

    /** @return the update checker, or {@code null} before onEnable completes */
    public UpdateChecker getUpdateChecker() { return updateChecker; }

    /** Shared helper to log a batch of fetch outcomes at the right severity. */
    private void logRemoteFetchResults(List<FetchResult> results) {
        for (FetchResult r : results) {
            switch (r.status()) {
                case FETCHED -> logger.remote(r.alias() + ": fetched (" + r.detail() + ")");
                case NOT_MODIFIED -> logger.remote(r.alias() + ": cached (304)");
                case ERROR_USING_CACHE -> logger.warning("[remote] " + r.alias()
                        + ": fetch failed — using cached copy (" + r.detail() + ")");
                case ERROR_NO_CACHE -> logger.error("[remote] " + r.alias()
                        + ": fetch failed and no cache available (" + r.detail() + ")");
                case SKIPPED_BY_POLICY -> logger.debug("[remote] " + r.alias()
                        + ": skipped (" + r.detail() + ")");
            }
        }
    }

    /**
     * Promotes a successfully-validated temp output to the real output path,
     * keeping the previous output as {@code <output>.previous} so a single
     * rollback target is always available.
     *
     * <p>Uses {@link StandardCopyOption#ATOMIC_MOVE} where possible (NTFS on
     * Windows and most POSIX filesystems support it); falls back to a plain
     * {@code REPLACE_EXISTING} move if the atomic flag is rejected.</p>
     */
    private File commitMerge(File tempOutput, File finalOutput) throws IOException {
        File previous = new File(finalOutput.getParentFile(), finalOutput.getName() + ".previous");
        File tempProvenance = PackMergeEngine.provenanceSidecar(tempOutput);
        File finalProvenance = PackMergeEngine.provenanceSidecar(finalOutput);
        File previousProvenance = PackMergeEngine.provenanceSidecar(previous);

        // Rotate the current output to .previous if it exists. We don't keep
        // more than one backup — just enough for a single-shot rollback.
        if (finalOutput.exists()) {
            moveWithAtomicFallback(finalOutput, previous);
            if (finalProvenance.exists()) {
                moveWithAtomicFallback(finalProvenance, previousProvenance);
            }
        }

        moveWithAtomicFallback(tempOutput, finalOutput);
        if (tempProvenance.exists()) {
            moveWithAtomicFallback(tempProvenance, finalProvenance);
        }
        return finalOutput;
    }

    private static void moveWithAtomicFallback(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * After a rollback, restore {@code lastProvenance} on the engine to the
     * sidecar of the (still-live) previous output so {@code /pm inspect} and
     * the plugin API keep reflecting what's actually shipping.
     */
    private void restoreProvenanceFrom(File outputFile) {
        File sidecar = PackMergeEngine.provenanceSidecar(outputFile);
        if (!sidecar.isFile()) return;
        try {
            String json = Files.readString(sidecar.toPath(), StandardCharsets.UTF_8);
            MergeProvenance restored = MergeProvenance.fromJson(json);
            if (restored != null) {
                mergeEngine.setLastProvenance(restored);
            }
        } catch (IOException e) {
            logger.debug("Could not restore provenance after rollback: " + e.getMessage());
        }
    }

    private void safeDelete(File f) {
        if (f == null || !f.exists()) return;
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException e) {
            logger.debug("Could not delete " + f.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Converts the merged Java pack to a Bedrock pack + Geyser custom-item mappings
     * when {@code bedrock.enabled} is set, and (optionally) deploys the outputs into
     * Geyser's folders. Best-effort: any failure is logged, never fatal to the merge.
     */
    private void convertBedrockIfEnabled(File mergedPack) {
        if (!configManager.isBedrockEnabled()) return;
        try {
            File bedrockOut = new File(getOutputFolder(), "bedrock");
            bedrockOut.mkdirs();
            String packName = "PackMerger-" + configManager.getServerName();
            BedrockConversionResult result = new BedrockConverter(
                    new BedrockConverterOptions(packName, configManager.isBedrockDebug()))
                    .convert(mergedPack.toPath(), bedrockOut.toPath());

            if (configManager.isBedrockDebug()) {
                result.warnings().forEach(w -> logger.debug("[bedrock] " + w));
            }
            if (!result.producedAnything()) {
                logger.info("[bedrock] no convertible custom items found ("
                        + result.warnings().size() + " note(s); enable bedrock.debug for detail)");
                lastBedrock = new BedrockState(Instant.now(), 0, null, null,
                        "no convertible custom items (" + result.warnings().size() + " note(s))");
                return;
            }
            logger.info("[bedrock] converted " + result.itemsConverted() + " item(s), "
                    + result.texturesCopied() + " texture(s) → " + result.mcpackFile().getFileName()
                    + " (manifest version " + result.manifestVersion() + ")");

            String deployedTo = null;
            String note = "not deployed (auto-deploy-to-geyser is off)";
            if (configManager.isBedrockAutoDeploy()) {
                File geyser = deployToGeyser(result);
                deployedTo = geyser == null ? null : geyser.getPath();
                note = geyser == null ? "Geyser folder not found; set bedrock.geyser-folder"
                        : "restart or reload Geyser to apply";
            }
            lastBedrock = new BedrockState(Instant.now(), result.itemsConverted(), result.manifestVersion(),
                    deployedTo, note);
            Bukkit.getPluginManager().callEvent(new PackBedrockConvertedEvent(
                    result.mcpackFile().toString(),
                    result.geyserMappingsFile() == null ? null : result.geyserMappingsFile().toString(),
                    result.itemsConverted()));
        } catch (Exception e) {
            logger.error("[bedrock] conversion failed (" + e.getMessage() + ")", e);
            lastBedrock = new BedrockState(Instant.now(), 0, null, null, "conversion failed: " + e.getMessage());
        }
    }

    /**
     * @return Geyser's data folder: {@code bedrock.geyser-folder} if set, else the data
     *         folder of an installed {@code Geyser-*} plugin, else {@code plugins/Geyser-Spigot}
     *         or {@code plugins/Geyser-Paper}; {@code null} if none was found
     */
    public File resolveGeyserFolder() {
        File plugins = getDataFolder().getAbsoluteFile().getParentFile();
        List<File> installed = new java.util.ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p.getName().startsWith("Geyser-")) installed.add(p.getDataFolder());
        }
        return GeyserFolder.resolve(configManager.getBedrockGeyserFolder(), plugins.getParentFile(), plugins,
                installed);
    }

    /**
     * Copies the generated {@code .mcpack} + mappings into the Geyser data folder.
     *
     * @return the folder deployed into, or {@code null} if it was not found or the copy failed
     */
    private File deployToGeyser(BedrockConversionResult result) {
        File geyserFolder = resolveGeyserFolder();
        if (geyserFolder == null || !geyserFolder.isDirectory()) {
            logger.warning("[bedrock] Geyser folder not found"
                    + (geyserFolder == null ? " (looked for an installed Geyser-* plugin, plugins/Geyser-Spigot and plugins/Geyser-Paper)"
                    : " at " + geyserFolder.getPath())
                    + " — skipping auto-deploy (set bedrock.geyser-folder)");
            return null;
        }
        try {
            File packs = new File(geyserFolder, "packs");
            packs.mkdirs();
            Files.copy(result.mcpackFile(),
                    new File(packs, result.mcpackFile().getFileName().toString()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            if (result.geyserMappingsFile() != null) {
                File mappings = new File(geyserFolder, "custom_mappings");
                mappings.mkdirs();
                Files.copy(result.geyserMappingsFile(),
                        new File(mappings, result.geyserMappingsFile().getFileName().toString()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("[bedrock] deployed to Geyser at " + geyserFolder.getPath()
                    + " (restart Geyser or run its reload to apply)");
            return geyserFolder;
        } catch (IOException e) {
            logger.warning("[bedrock] auto-deploy failed: " + e.getMessage());
            return null;
        }
    }

    /** Copies the merged zip into every {@code merge.output-copy-to} folder (best-effort). */
    private void copyOutput(File outputFile) {
        List<String> targets = configManager.getOutputCopyTo();
        if (targets.isEmpty()) return;
        File plugins = getDataFolder().getAbsoluteFile().getParentFile();
        for (OutputCopier.Result r : OutputCopier.copy(outputFile, targets, plugins, getPacksFolder())) {
            if (r.ok()) {
                logger.info("[output] copied merged pack to " + r.file().getPath());
            } else {
                logger.warning("[output] could not copy merged pack to " + r.target() + ": " + r.detail());
            }
        }
    }

    /**
     * Computes the SHA-1 hash of a file using buffered reads.
     *
     * <p>Minecraft's resource pack protocol requires the SHA-1 hash to verify pack integrity.
     * The client compares this hash against its cached version to decide whether to re-download.</p>
     *
     * @param file the file to hash
     * @return the raw SHA-1 hash bytes (20 bytes)
     * @throws Exception if the file cannot be read or SHA-1 is unavailable (should never happen
     *                   as SHA-1 is a mandatory JCA algorithm)
     */
    private byte[] computeSha1(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192]; // 8 KB buffer for efficient disk I/O
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    /**
     * Converts a byte array to its lowercase hexadecimal string representation.
     *
     * @param bytes the bytes to convert
     * @return the hex string (e.g. {@code "a1b2c3..."})
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // JavaPlugin delegates — so the rest of the codebase can keep calling
    // plugin.getConfig() / plugin.getLogger() / plugin.getDataFolder() without
    // learning that the Bootstrap is no longer a JavaPlugin. Every delegate
    // forwards to the loader that the server actually registered.
    // -------------------------------------------------------------------------

    /** @return the JavaPlugin that the server registered (the loader instance) */
    public JavaPlugin getLoader() { return loader; }

    public FileConfiguration getConfig() { return loader.getConfig(); }

    public void saveDefaultConfig() { loader.saveDefaultConfig(); }

    public void reloadConfig() { loader.reloadConfig(); }

    public void saveConfig() { loader.saveConfig(); }

    public Logger getLogger() { return loader.getLogger(); }

    public Server getServer() { return loader.getServer(); }

    public LifecycleEventManager<Plugin> getLifecycleManager() { return loader.getLifecycleManager(); }

    public void saveResource(String resourcePath, boolean replace) { loader.saveResource(resourcePath, replace); }

    // -------------------------------------------------------------------------
    // Directory helpers — all paths are relative to the plugin's data folder
    // -------------------------------------------------------------------------

    /**
     * Returns the directory where source resource packs are placed.
     *
     * @return {@code plugins/PackMerger/packs/}
     */
    public File getPacksFolder() {
        return new File(loader.getDataFolder(), "packs");
    }

    /** @return the plugin's data folder (delegate to loader) */
    public File getDataFolder() { return loader.getDataFolder(); }

    /**
     * Returns the directory where merged pack output is written.
     *
     * @return {@code plugins/PackMerger/output/}
     */
    public File getOutputFolder() {
        return new File(loader.getDataFolder(), "output");
    }

    /**
     * Returns the directory where persistent cache files are stored.
     *
     * @return {@code plugins/PackMerger/cache/}
     */
    public File getCacheFolder() {
        return new File(loader.getDataFolder(), "cache");
    }

    /**
     * Returns the output file path for the merged pack.
     *
     * <p>If the server has a per-server pack configuration and the server name is not
     * "default", the filename is prefixed with the server name (e.g.
     * {@code lobby-merged-pack.zip}). Otherwise, it is {@code merged-pack.zip}.</p>
     *
     * @return the {@link File} where the merged pack zip should be written
     */
    public File getOutputFile() {
        String serverName = configManager.getServerName();
        ConfigManager.ServerPackConfig serverConfig = configManager.getServerPackConfig();
        // Use a server-specific filename for multi-server setups
        if (serverConfig != null && !serverName.equalsIgnoreCase("default")) {
            return new File(getOutputFolder(), serverName + "-merged-pack.zip");
        }
        return new File(getOutputFolder(), "merged-pack.zip");
    }

    // -------------------------------------------------------------------------
    // Component accessors
    // -------------------------------------------------------------------------

    /** @return the plugin's configuration manager */
    public ConfigManager getConfigManager() { return configManager; }

    /** @return the plugin's message manager */
    public MessageManager getMessageManager() { return messageManager; }

    /** @return the plugin's colored console logger */
    public PluginLogger getPluginLogger() { return logger; }

    /** @return the merge engine responsible for combining resource packs */
    public PackMergeEngine getMergeEngine() { return mergeEngine; }

    /** @return the validator used to check merged pack integrity */
    public PackValidator getValidator() { return validator; }

    /** @return the distributor that sends packs to players */
    public PackDistributor getDistributor() { return distributor; }

    /** @return the link to the PackMerger proxy plugin, or {@code null} before enable */
    public ProxyLink getProxyLink() { return proxyLink; }

    /** @return the active upload provider */
    public UploadProvider getUploadProvider() { return uploadProvider; }

    /**
     * @return provenance for the most recent successful merge, or {@code null}
     *         if no merge has completed during this plugin's lifetime and no
     *         prior {@code .merge-provenance.json} was restored on startup
     */
    @Override
    public MergeProvenance getLastMergeProvenance() {
        return mergeEngine == null ? null : mergeEngine.getLastProvenance();
    }

    // -------------------------------------------------------------------------
    // PackMergerApi — stable third-party surface.
    // See sh.pcx.packmerger.api.PackMergerApi for contract; most methods here
    // are just thin delegates to existing accessors. Tagged @Experimental in
    // the interface until 1.2.
    // -------------------------------------------------------------------------

    /** @return the stable third-party-facing API instance for this plugin */
    public PackMergerApi getApi() { return this; }

    @Override
    public String getCurrentPackSha1Hex() {
        PublishedPack pack = publishedPack.get();
        return pack == null ? null : pack.sha1Hex();
    }

    @Override
    public CompletableFuture<Void> triggerMerge() {
        return mergeAndUpload(null);
    }

    @Override
    public void registerPack(Plugin owner, String alias, java.nio.file.Path source, PackOptions options) {
        if (apiPackManager.register(owner, alias, source, options)
                && (options == null || options.mergeNow())) {
            requestMerge("pack '" + alias + "' registered by " + owner.getName());
        }
    }

    @Override
    public void registerPack(Plugin owner, String alias, java.net.URI url, PackOptions options) {
        if (apiPackManager.register(owner, alias, url, options)
                && (options == null || options.mergeNow())) {
            requestMerge("pack '" + alias + "' registered by " + owner.getName());
        }
    }

    @Override
    public boolean unregisterPack(String alias) {
        boolean removed = apiPackManager.unregister(alias);
        if (removed) requestMerge("pack '" + alias + "' unregistered");
        return removed;
    }

    @Override
    public List<RegisteredPack> getRegisteredPacks() {
        return apiPackManager == null ? List.of() : apiPackManager.list();
    }

    /** Called when another plugin is disabled: drops the packs it registered. */
    public void onPluginDisabled(Plugin disabled) {
        if (apiPackManager == null || disabled == loader) return;
        if (!apiPackManager.unregisterOwner(disabled.getName()).isEmpty()) {
            requestMerge(disabled.getName() + " was disabled");
        }
    }

    /**
     * Restores merge provenance from the current output's sidecar
     * ({@code <output>.provenance.json}) so /pm inspect and the plugin API have
     * a sensible answer before the first merge runs. Silently skipped if the
     * file doesn't exist or fails to parse.
     */
    private void loadLastProvenance() {
        File provenanceFile = PackMergeEngine.provenanceSidecar(getOutputFile());
        if (!provenanceFile.isFile()) return;
        try {
            String json = Files.readString(provenanceFile.toPath(), StandardCharsets.UTF_8);
            MergeProvenance restored = MergeProvenance.fromJson(json);
            if (restored != null) {
                mergeEngine.setLastProvenance(restored);
                logger.debug("Restored merge provenance from disk");
            }
        } catch (Exception e) {
            logger.debug("Failed to restore merge provenance: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Pack state accessors
    // -------------------------------------------------------------------------

    /**
     * @return the pack currently offered to players (URL, SHA-1 and pack UUID, always
     *         consistent with each other), or {@code null} if nothing is published yet
     */
    public PublishedPack getPublishedPack() { return publishedPack.get(); }

    /** @return how the live pack was published, or {@code null} before the first publish */
    public UploadChain.Outcome getLastUploadOutcome() { return lastUploadOutcome; }

    /** @return the name of the active upload provider ("self-host", "s3", "polymath") */
    public String getUploadProviderName() { return uploadProviderName; }

    /**
     * @return the self-host provider whose state /pm status reports: the active provider if
     *         it is self-host, otherwise a self-host fallback that has been started, else {@code null}
     */
    public SelfHostProvider getSelfHostProvider() {
        if (uploadProvider instanceof SelfHostProvider selfHost) return selfHost;
        return fallbackProviders.get("self-host") instanceof SelfHostProvider selfHost ? selfHost : null;
    }

    /**
     * @return a one-paragraph notice for admins when players probably can't download the
     *         pack (fallback used, verification failed, or a non-routable self-host URL), or
     *         {@code null} when hosting looks healthy
     */
    public String getHostingProblem() {
        UploadChain.Outcome outcome = lastUploadOutcome;
        if (outcome == null) return null;
        if (outcome.fallback()) {
            return "Pack hosting problem: published through " + outcome.describe();
        }
        SelfHostProvider selfHost = getSelfHostProvider();
        if (selfHost != null && "self-host".equals(outcome.provider())) {
            if (selfHost.getVerification().state() == SelfHostProvider.VerificationState.FAILED) {
                return "Self-host verification failed: " + selfHost.getVerification().detail();
            }
            if (selfHost.getResolution() != null && !selfHost.getResolution().routable()) {
                return selfHost.getResolution().advice();
            }
        }
        return outcome.degraded() ? "Pack hosting problem: published through " + outcome.describe() : null;
    }

    /** Sends a plain message to online players with {@code packmerger.admin} (thread-safe). */
    public void alertAdmins(String message) {
        net.kyori.adventure.text.Component text = net.kyori.adventure.text.Component.text(message,
                net.kyori.adventure.text.format.NamedTextColor.RED);
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("packmerger.admin")) p.sendMessage(text);
        }
    }

    /** @return the public download URL of the published pack, or {@code null} if none is published */
    public String getCurrentPackUrl() {
        PublishedPack pack = publishedPack.get();
        return pack == null ? null : pack.url();
    }

    /** @return the hex-encoded SHA-1 of the published pack, or {@code null} if none is published */
    public String getCurrentPackHashHex() { return getCurrentPackSha1Hex(); }

    /** @return the timestamp of the last successful merge, or {@code null} if no merge has occurred */
    public LocalDateTime getLastMergeTime() { return lastMergeTime; }

    /** @return {@code true} if a merge is currently in progress */
    public boolean isMerging() { return merging.get(); }

    /**
     * Returns the last merge time as a human-readable string for display in the status command.
     *
     * @return the formatted timestamp (e.g. {@code "2025-01-15 14:30:00"}), or {@code "Never"}
     *         if no merge has been performed yet
     */
    public String getFormattedLastMergeTime() {
        if (lastMergeTime == null) return "Never";
        return lastMergeTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
