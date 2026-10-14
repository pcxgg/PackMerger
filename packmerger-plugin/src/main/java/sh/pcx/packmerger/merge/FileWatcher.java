package sh.pcx.packmerger.merge;

import sh.pcx.packmerger.PackMergerBootstrap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import sh.pcx.packmerger.PluginLogger;

/**
 * Monitors the packs folder for file system changes and triggers automatic re-merges
 * (hot-reload functionality).
 *
 * <p>The watcher runs on a dedicated daemon thread that uses the Java NIO
 * {@link WatchService} API to receive file create/modify/delete events from the OS.
 * To avoid excessive re-merges during bulk file operations (e.g. copying a new pack
 * folder), events are debounced — the merge is only triggered after no new events
 * have been received for the configured debounce period.</p>
 *
 * <p>Lifecycle: created and started by {@link PackMerger#startFileWatcher()}, stopped
 * by {@link PackMerger#stopFileWatcher()}. The watcher thread is set as a daemon thread
 * so it won't prevent JVM shutdown.</p>
 *
 * @see PackMerger#startFileWatcher()
 * @see PackMerger#stopFileWatcher()
 */
public class FileWatcher {

    /** Reference to the owning plugin for triggering merges and reading config. */
    private final PackMergerBootstrap plugin;

    /** Colored console logger. */
    private final PluginLogger logger;

    /** The dedicated watch thread, or {@code null} if not running. */
    private Thread watchThread;

    /** Flag indicating whether the watcher loop should continue running. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** The NIO watch service handle, used for cleanup on stop. */
    private WatchService watchService;

    /**
     * Creates a new file watcher bound to the given plugin.
     *
     * @param plugin the owning PackMergerBootstrap plugin
     */
    public FileWatcher(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
    }

    /**
     * Starts the file watcher on a dedicated daemon thread.
     *
     * <p>If already running, this method is a no-op. The watcher registers for
     * CREATE, MODIFY, and DELETE events on the packs folder <em>and every directory
     * below it</em> (except dot-directories such as {@code .remote-cache/} and
     * {@code .plugin-packs/}, which PackMerger writes itself), so editing a file deep
     * inside an unzipped pack folder triggers a merge too. Directories created later
     * are registered as they appear. Events are debounced using a timestamp-based
     * approach: each relevant event updates a "last event time" counter, and the
     * merge only fires when the polling loop detects that the debounce period has
     * elapsed since the last event.</p>
     */
    public void start() {
        if (running.get()) return;

        Path packsPath = plugin.getPacksFolder().toPath().toAbsolutePath().normalize();
        if (!packsPath.toFile().exists()) {
            packsPath.toFile().mkdirs();
        }

        running.set(true);
        watchThread = new Thread(() -> {
            try {
                watchService = FileSystems.getDefault().newWatchService();
                Map<WatchKey, Path> watchedDirs = new HashMap<>();
                registerTree(packsPath, packsPath, watchedDirs);

                logger.info("File watcher started for: " + packsPath + " (" + watchedDirs.size() + " folder(s))");

                // Debounce state: tracks the timestamp of the last relevant file event.
                // When this is non-zero and the debounce period elapses, a merge is triggered.
                AtomicLong lastEventTime = new AtomicLong(0);
                int debounceMs = plugin.getConfigManager().getHotReloadDebounceSeconds() * 1000;

                while (running.get()) {
                    WatchKey key;
                    try {
                        // Poll with a 1-second timeout so we can check the debounce timer regularly
                        key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (key == null) {
                        // No events this cycle — check if we've been waiting long enough to trigger
                        long lastTime = lastEventTime.get();
                        if (lastTime > 0 && System.currentTimeMillis() - lastTime >= debounceMs) {
                            // Debounce period elapsed since last event — trigger the merge
                            lastEventTime.set(0);
                            triggerMerge();
                        }
                        continue;
                    }

                    Path dir = watchedDirs.get(key);
                    boolean hasRelevantEvent = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        // OVERFLOW: the OS dropped events. Something changed, so merge.
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            hasRelevantEvent = true;
                            continue;
                        }
                        if (dir == null) continue;

                        Path changed = dir.resolve((Path) event.context());
                        Path relative = packsPath.relativize(changed);
                        if (isRelevant(relative)) {
                            hasRelevantEvent = true;
                            logger.debug("File change detected: " + kind.name() + " " + relative);
                        }
                        // Watch directories that appear later (a pack folder moved in,
                        // or a new sub-folder inside one).
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)
                                && !hasDotSegment(relative)) {
                            registerTree(changed, packsPath, watchedDirs);
                        }
                    }

                    if (hasRelevantEvent) {
                        // Reset the debounce timer — we'll wait for the full debounce period
                        // from this point before triggering
                        lastEventTime.set(System.currentTimeMillis());
                        logger.info("Pack file change detected, waiting " +
                                plugin.getConfigManager().getHotReloadDebounceSeconds() + "s before merging...");
                    }

                    // Reset the watch key so we continue receiving events
                    boolean valid = key.reset();
                    if (!valid) {
                        watchedDirs.remove(key);
                        if (packsPath.equals(dir)) {
                            // The packs folder itself was deleted or became inaccessible
                            logger.warning("File watcher key invalidated, stopping watcher");
                            break;
                        }
                        // A nested folder was deleted; its parent reported the deletion.
                    }
                }
            } catch (ClosedWatchServiceException e) {
                // Expected during shutdown — stop() closes the watch service
            } catch (IOException e) {
                logger.error("File watcher error", e);
            }
        }, "PackMerger-FileWatcher");

        // Daemon thread so it doesn't prevent server shutdown
        watchThread.setDaemon(true);
        watchThread.start();
    }

    /**
     * Whether a change at {@code relative} (relative to the packs folder) should
     * trigger a merge. Anything under a dot-prefixed segment is ignored (PackMerger's
     * own {@code .remote-cache/} and {@code .plugin-packs/} staging, editor swap
     * files). At the top level only pack-shaped entries count: the custom
     * {@code pack.mcmeta}/{@code pack.png} overrides, {@code .zip} archives and
     * directories (names without an extension). Inside a pack folder every file counts.
     */
    static boolean isRelevant(Path relative) {
        if (relative.toString().isEmpty() || hasDotSegment(relative)) return false;
        if (relative.getNameCount() > 1) return true;
        String fileName = relative.toString();
        return fileName.equals("pack.mcmeta") || fileName.equals("pack.png")
                || fileName.endsWith(".zip") || !fileName.contains(".");
    }

    private static boolean hasDotSegment(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) return true;
        }
        return false;
    }

    /** Registers {@code start} and every non-dot directory below it. */
    private void registerTree(Path start, Path root, Map<WatchKey, Path> watchedDirs) {
        int[] failed = {0};
        try (var dirs = Files.walk(start)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> d.equals(root) || !hasDotSegment(root.relativize(d)))
                    .forEach(d -> {
                        try {
                            WatchKey k = d.register(watchService,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY,
                                    StandardWatchEventKinds.ENTRY_DELETE);
                            watchedDirs.put(k, d);
                        } catch (IOException e) {
                            failed[0]++;
                            logger.debug("File watcher could not watch " + d + ": " + e.getMessage());
                        }
                    });
        } catch (IOException | UncheckedIOException e) {
            logger.debug("File watcher could not scan " + start + ": " + e.getMessage());
        }
        if (failed[0] > 0) {
            logger.warning("File watcher could not watch " + failed[0] + " folder(s) under " + start
                    + "; hot-reload will miss changes there. On Linux raise fs.inotify.max_user_watches"
                    + " (sysctl), or disable hot-reload and use /pm merge.");
        }
    }

    /**
     * Stops the file watcher and releases all resources.
     *
     * <p>Closes the watch service (which unblocks the polling thread), interrupts the
     * thread, and clears the reference. Safe to call multiple times.</p>
     */
    public void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                // Swallowed intentionally — we're shutting down and the resource will be
                // cleaned up by the JVM regardless
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        logger.info("File watcher stopped");
    }

    /**
     * Called after the debounce period elapses to initiate an automatic merge.
     * Delegates to the plugin's merge-and-upload pipeline with no command sender
     * (automated merge, no in-game feedback).
     */
    private void triggerMerge() {
        logger.info("Debounce complete, triggering auto-merge...");
        // null sender = no in-game messages (this is an automated merge)
        plugin.mergeAndUpload(null);
    }
}
