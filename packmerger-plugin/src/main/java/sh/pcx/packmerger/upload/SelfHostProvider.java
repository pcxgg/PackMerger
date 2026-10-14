package sh.pcx.packmerger.upload;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;
import sh.pcx.packmerger.config.ConfigManager;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Upload provider that serves the merged resource pack from a built-in HTTP server.
 *
 * <p>This is the simplest upload option — no external service is needed. The plugin starts
 * an embedded {@link HttpServer} on the configured port and serves the pack file directly
 * at the {@code /pack} endpoint. The "upload" operation snapshots the merged zip into
 * {@code output/.self-host/<sha1>.zip} and swaps the served file reference atomically.
 * Serving a snapshot (instead of the live output path) means a merge that rewrites
 * {@code output/} never changes the bytes behind an already-advertised SHA-1; the new
 * pack is only served once it is published.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li><strong>Concurrent download limiting</strong> — a {@link Semaphore} caps the number
 *       of simultaneous downloads to prevent the server from being overwhelmed</li>
 *   <li><strong>Auto URL detection</strong> — if no public URL is configured, the server IP
 *       is read from {@code server.properties} to construct the download URL</li>
 *   <li><strong>Thread pool</strong> — uses a fixed thread pool sized to the number of
 *       available processors (minimum 4) for handling HTTP requests</li>
 * </ul>
 *
 * <p>Lifecycle: started by {@link PackMerger#initUploadProvider()} when the self-host
 * provider is selected, stopped by {@link PackMerger#onDisable()} or when switching
 * providers.</p>
 *
 * @see UploadProvider
 */
public class SelfHostProvider implements UploadProvider {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMergerBootstrap plugin;

    /** Colored console logger. */
    private final PluginLogger logger;

    /** The embedded HTTP server instance, or {@code null} if not started. */
    private HttpServer server;

    /** Port the HTTP server was bound to (or attempted), used to decide whether a reload can reuse it. */
    private int boundPort = -1;

    /** Snapshot that was served before the current one; kept for in-flight downloads. */
    private final AtomicReference<File> previousFile = new AtomicReference<>();

    /**
     * Atomic reference to the currently served pack file. Updated on each "upload"
     * (merge completion) so that in-flight requests continue serving the old file
     * while new requests get the latest version.
     */
    private final AtomicReference<File> currentFile = new AtomicReference<>();

    /**
     * Semaphore for limiting concurrent downloads. {@code null} if rate limiting is disabled
     * (rate-limit set to 0).
     */
    private Semaphore rateLimiter;

    /**
     * Creates a new self-host provider.
     *
     * @param plugin the owning PackMergerBootstrap plugin
     */
    public SelfHostProvider(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
    }

    /**
     * Starts the built-in HTTP server on the configured port.
     *
     * <p>The server binds to all interfaces (0.0.0.0) and registers a single context
     * at {@code /pack} that serves the current merged pack file. If the port is already
     * in use, an error is logged but the plugin continues to function (uploads will
     * work but self-hosting won't).</p>
     */
    public void start() {
        ConfigManager config = plugin.getConfigManager();
        int port = config.getSelfHostPort();
        boundPort = port;
        applyRateLimit(config.getSelfHostRateLimit());

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/pack", this::handleRequest);
            // Thread pool sized to available processors, minimum 4 for reasonable concurrency
            server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
            server.start();
            logger.upload("Self-host HTTP server started on port " + port);
        } catch (IOException e) {
            logger.error("Failed to start self-host HTTP server on port " + port + ": " + e.getMessage());
        }
    }

    /** (Re)creates the download limiter; 0 or less disables it. */
    private void applyRateLimit(int rateLimit) {
        rateLimiter = rateLimit > 0 ? new Semaphore(rateLimit) : null;
    }

    /**
     * Applies changed settings after {@code /pm reload} without restarting the HTTP
     * server, so the advertised URL keeps answering throughout the reload.
     *
     * @return {@code false} if the server must be restarted instead (port changed or
     *         it is not running)
     */
    public boolean reconfigure() {
        ConfigManager config = plugin.getConfigManager();
        if (server == null || config.getSelfHostPort() != boundPort) return false;
        applyRateLimit(config.getSelfHostRateLimit());
        return true;
    }

    /** @return {@code true} if the HTTP server is bound and serving */
    public boolean isRunning() {
        return server != null;
    }

    /**
     * Stops the HTTP server with a 2-second grace period for in-flight requests.
     * Safe to call multiple times.
     */
    public void stop() {
        if (server != null) {
            server.stop(2); // 2-second grace period for in-flight downloads
            server = null;
            logger.upload("Self-host HTTP server stopped");
        }
    }

    /**
     * Handles incoming HTTP requests to the {@code /pack} endpoint.
     *
     * <p>Only GET requests are accepted. The handler:</p>
     * <ol>
     *   <li>Rejects non-GET methods with 405</li>
     *   <li>Returns 503 if no pack is available yet</li>
     *   <li>Returns 429 if the rate limit is exceeded</li>
     *   <li>Streams the pack file with appropriate Content-Type and Content-Disposition headers</li>
     * </ol>
     *
     * <p>The rate limiter semaphore is acquired before streaming and released in a
     * finally block to ensure permits are always returned, even on I/O errors.</p>
     *
     * @param exchange the HTTP exchange object
     * @throws IOException if writing the response fails
     */
    private void handleRequest(HttpExchange exchange) throws IOException {
        // Only accept GET requests
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); // 405 Method Not Allowed
            exchange.close();
            return;
        }

        File file = currentFile.get();
        if (file == null || !file.exists()) {
            // No pack has been merged yet — return 503 Service Unavailable
            byte[] msg = "Resource pack not available yet".getBytes();
            exchange.sendResponseHeaders(503, msg.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg);
            }
            exchange.close();
            return;
        }

        // Check rate limit before starting the download. Capture the limiter so a
        // reconfigure() mid-download releases the permit on the semaphore it came from.
        Semaphore limiter = rateLimiter;
        if (limiter != null && !limiter.tryAcquire()) {
            // Too many concurrent downloads — return 429 Too Many Requests
            byte[] msg = "Too many concurrent downloads, try again later".getBytes();
            exchange.sendResponseHeaders(429, msg.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg);
            }
            exchange.close();
            return;
        }

        try {
            long fileSize = file.length();
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"resource-pack.zip\"");
            exchange.sendResponseHeaders(200, fileSize);

            // Stream the file in 8 KB chunks to avoid loading the entire pack into memory
            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        } finally {
            // Always release the rate limiter permit, even if the download was interrupted
            if (limiter != null) {
                limiter.release();
            }
            exchange.close();
        }
    }

    /**
     * Makes the given file available for download via the HTTP server.
     *
     * <p>The file is snapshotted to {@code output/.self-host/<sha1>.zip} (hard link when
     * possible, otherwise a copy) and the served reference is swapped atomically. The
     * snapshot served before is kept for downloads already in flight; older ones are
     * deleted.</p>
     *
     * @param file    the merged pack zip file to serve
     * @param sha1Hex the SHA-1 hash, used to name the snapshot
     * @return the public download URL for the pack (e.g. {@code http://1.2.3.4:8080/pack})
     * @throws IOException if the HTTP server is not running (so the URL would be dead)
     *                     or the snapshot cannot be written
     */
    @Override
    public String upload(File file, String sha1Hex) throws Exception {
        if (server == null) {
            throw new IOException("the self-host HTTP server is not running (port " + boundPort
                    + " could not be bound); refusing to advertise a dead URL");
        }

        File snapshot = snapshot(file, sha1Hex);
        File old = currentFile.getAndSet(snapshot);
        if (old != null && !old.equals(snapshot)) {
            File older = previousFile.getAndSet(old);
            if (older != null && !older.equals(snapshot) && !older.equals(old)) {
                Files.deleteIfExists(older.toPath());
            }
        }
        pruneSnapshots(snapshot.getParentFile());

        String publicUrl = plugin.getConfigManager().getSelfHostPublicUrl();
        if (publicUrl == null || publicUrl.isEmpty()) {
            // Auto-detect the URL from server.properties server-ip and configured port
            publicUrl = buildAutoUrl();
        }

        // Ensure the URL ends with /pack to match the HTTP server context
        if (!publicUrl.endsWith("/pack")) {
            if (publicUrl.endsWith("/")) {
                publicUrl += "pack";
            } else {
                publicUrl += "/pack";
            }
        }

        return publicUrl;
    }

    /** Directory holding the served snapshots. */
    private File snapshotDir() {
        return new File(plugin.getOutputFolder(), ".self-host");
    }

    /** Hard-links (or copies) {@code file} to {@code .self-host/<sha1>.zip}, atomically. */
    private File snapshot(File file, String sha1Hex) throws IOException {
        File dir = snapshotDir();
        Files.createDirectories(dir.toPath());
        File target = new File(dir, sha1Hex + ".zip");
        if (target.isFile() && target.length() == file.length()) return target;

        File temp = new File(dir, sha1Hex + ".zip.tmp");
        Files.deleteIfExists(temp.toPath());
        try {
            Files.createLink(temp.toPath(), file.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            if (e instanceof FileAlreadyExistsException) throw (IOException) e;
            Files.copy(file.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** Deletes snapshots that are neither served now nor were served just before. */
    private void pruneSnapshots(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        File current = currentFile.get();
        File previous = previousFile.get();
        for (File f : files) {
            if (f.equals(current) || f.equals(previous)) continue;
            try {
                Files.deleteIfExists(f.toPath());
            } catch (IOException e) {
                logger.debug("Could not delete old self-host snapshot " + f.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Builds the public URL automatically from the server IP and configured port.
     *
     * @return the auto-detected URL (e.g. {@code http://1.2.3.4:8080})
     */
    private String buildAutoUrl() {
        int port = plugin.getConfigManager().getSelfHostPort();
        String serverIp = readServerIp();
        return "http://" + serverIp + ":" + port;
    }

    /**
     * Reads the {@code server-ip} property from {@code server.properties}.
     *
     * <p>If the property is empty or the file cannot be read, defaults to "localhost".
     * In production, operators should configure the {@code public-url} setting explicitly
     * to ensure players can reach the server from outside the network.</p>
     *
     * @return the server IP address, or "localhost" as a fallback
     */
    private String readServerIp() {
        File serverProperties = new File("server.properties");
        if (serverProperties.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(serverProperties)) {
                props.load(fis);
                String ip = props.getProperty("server-ip", "").trim();
                if (!ip.isEmpty()) {
                    return ip;
                }
            } catch (IOException e) {
                // Fall through to default
            }
        }
        return "localhost";
    }
}
