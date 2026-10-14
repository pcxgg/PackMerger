package sh.pcx.packmerger.upload;

import com.sun.net.httpserver.HttpServer;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;
import sh.pcx.packmerger.config.ConfigManager;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Upload provider that serves the merged resource pack from a built-in HTTP server.
 *
 * <p>No external service is needed: an embedded {@link HttpServer} serves the pack at
 * {@code /pack} (see {@link PackHttpHandler} for HEAD, ETag/304, Range and exact path
 * matching). The "upload" operation snapshots the merged zip into
 * {@code output/.self-host/<sha1>.zip} and swaps the served file atomically, so a merge
 * that rewrites {@code output/} never changes the bytes behind an advertised SHA-1.</p>
 *
 * <p>The advertised URL is resolved by {@link SelfHostAddress} (public-url, public-host,
 * opt-in public IP lookup, server-ip, bind address). A non-routable result is logged as a
 * warning on every upload. After each upload {@link #check(String)} verifies the URL by
 * downloading the first KiB through {@code 127.0.0.1} and through the advertised URL (the
 * second catches routers without NAT hairpinning, a closed firewall, or a reverse proxy
 * pointing elsewhere); the result is kept for {@code /pm status} and drives
 * {@code upload.fallback}. Verification runs on the publishing thread, never the main
 * thread.</p>
 *
 * <p>Lifecycle: started by {@code PackMergerBootstrap#initUploadProvider()} when
 * self-host is the provider (or a fallback), stopped on disable or when switching
 * providers/ports.</p>
 */
public class SelfHostProvider implements UploadProvider, UploadChain.Step {

    /** Timeout of each verification request. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);

    /** State of the last reachability verification. */
    public enum VerificationState { PENDING, PASSED, FAILED, SKIPPED }

    /**
     * Result of the last reachability verification.
     *
     * @param state  outcome
     * @param detail human-readable reason (what was checked, or what failed)
     * @param at     when it was recorded
     */
    public record Verification(VerificationState state, String detail, Instant at) {}

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;

    /** The embedded HTTP server instance, or {@code null} if not started (or binding failed). */
    private volatile HttpServer server;

    /** Request threads; shut down with the server so reloads don't leak threads. */
    private ExecutorService executor;

    /** Port the HTTP server was bound to (or attempted), used to decide whether a reload can reuse it. */
    private volatile int boundPort = -1;

    /** Pack served now; {@code null} until the first upload. */
    private final AtomicReference<PackHttpHandler.ServedPack> current = new AtomicReference<>();

    /**
     * Pack uploaded but not yet committed. It is only served to requests carrying its
     * verification token ({@code ?pm-verify=<token>}), so the reachability check can fetch
     * it through the advertised URL while everyone else keeps getting the pack whose SHA-1
     * is published. {@link #commit()} makes it the current pack.
     */
    private final AtomicReference<Pending> pending = new AtomicReference<>();

    /** An uploaded, not yet committed pack and the token that unlocks it for verification. */
    private record Pending(PackHttpHandler.ServedPack pack, String token) {}

    /** Query parameter carrying the verification token. */
    static final String VERIFY_PARAM = "pm-verify";

    /** Pack served before the current one; its snapshot is kept for in-flight downloads. */
    private final AtomicReference<PackHttpHandler.ServedPack> previous = new AtomicReference<>();

    /** Download limiter; {@code null} = unlimited. */
    private volatile Semaphore rateLimiter;

    /** Exact request paths that serve the pack. */
    private volatile Set<String> paths = Set.of(SelfHostAddress.PACK_PATH);

    /** How the advertised URL was resolved by the last upload; {@code null} before the first. */
    private volatile SelfHostAddress.Resolution resolution;

    /** Last verification result. */
    private volatile Verification verification =
            new Verification(VerificationState.PENDING, "no pack published yet", Instant.now());

    /** Public IP from the opt-in lookup; looked up once per provider lifecycle (and per reload). */
    private volatile String detectedIp;
    private volatile boolean detectionDone;

    public SelfHostProvider(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
    }

    /**
     * Starts the built-in HTTP server on the configured port, on all interfaces. A bind
     * failure is logged; {@link #upload} then fails (so no dead URL is advertised) and
     * {@code upload.fallback} can take over.
     */
    public void start() {
        ConfigManager config = plugin.getConfigManager();
        SelfHostPort portInfo = config.getSelfHostPortInfo();
        int port = portInfo.port();
        boundPort = port;
        for (String warning : portInfo.warnings()) logger.warning(warning);
        applySettings(config);

        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/", new PackHttpHandler(this::packFor, () -> rateLimiter, () -> paths));
            executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
            http.setExecutor(executor);
            http.start();
            server = http;
            logger.upload("Self-host HTTP server started on port " + port
                    + (portInfo.derived() ? " (Minecraft port + 1)" : ""));
        } catch (IOException e) {
            logger.error("Failed to start self-host HTTP server on port " + port + ": " + e.getMessage()
                    + ". Set upload.self-host.port to a free port (-1 = Minecraft port + 1; on a hosting"
                    + " panel use a port allocated to this server).");
            verification = new Verification(VerificationState.FAILED,
                    "HTTP server could not bind port " + port + ": " + e.getMessage(), Instant.now());
        }
    }

    /** The pack a request gets: the pending one when it carries its token, else the current one. */
    private PackHttpHandler.ServedPack packFor(String rawQuery) {
        Pending p = pending.get();
        if (p != null && rawQuery != null) {
            for (String param : rawQuery.split("&")) {
                if (param.equals(VERIFY_PARAM + "=" + p.token())) return p.pack();
            }
        }
        return current.get();
    }

    /** Applies the settings that can change without a restart. */
    private void applySettings(ConfigManager config) {
        int rateLimit = config.getSelfHostRateLimit();
        rateLimiter = rateLimit > 0 ? new Semaphore(rateLimit) : null;
        Set<String> p = new LinkedHashSet<>();
        p.add(SelfHostAddress.PACK_PATH);
        String publicUrl = config.getSelfHostPublicUrl();
        if (!publicUrl.isEmpty()) {
            // Serve the public-url path too, for reverse proxies that forward it unchanged.
            String path = SelfHostAddress.pathOf(SelfHostAddress.normalizePublicUrl(publicUrl));
            if (path != null && !path.equals("/")) p.add(path);
        }
        paths = Set.copyOf(p);
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
        for (String warning : config.getSelfHostPortInfo().warnings()) logger.warning(warning);
        applySettings(config);
        // Host settings may have changed: resolve (and re-detect) on the next upload.
        detectionDone = false;
        detectedIp = null;
        return true;
    }

    /** @return {@code true} if the HTTP server is bound and serving */
    public boolean isRunning() {
        return server != null;
    }

    /** @return the port the HTTP server is bound to (or tried to bind) */
    public int getBoundPort() {
        return boundPort;
    }

    /** @return how the advertised URL was resolved by the last upload, or {@code null} */
    public SelfHostAddress.Resolution getResolution() {
        return resolution;
    }

    /** @return the last reachability verification result (never {@code null}) */
    public Verification getVerification() {
        return verification;
    }

    /**
     * Stops the HTTP server with a 2-second grace period for in-flight requests.
     * Safe to call multiple times.
     */
    public void stop() {
        HttpServer http = server;
        if (http != null) {
            server = null;
            http.stop(2); // 2-second grace period for in-flight downloads
            if (executor != null) executor.shutdown();
            logger.upload("Self-host HTTP server stopped");
        }
    }

    /**
     * Snapshots the given file and returns the advertised URL. The snapshot is staged:
     * it is served at the public path only after {@link #commit()} (called by the upload
     * chain right before the URL and SHA-1 are published), so verification can run while
     * players keep getting bytes that match the published SHA-1.
     *
     * <p>The file is snapshotted to {@code output/.self-host/<sha1>.zip} (hard link when
     * possible, otherwise a copy). The snapshot served before is kept for downloads already
     * in flight; older ones are deleted on commit.</p>
     *
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
        pending.set(new Pending(new PackHttpHandler.ServedPack(snapshot, sha1Hex),
                java.util.UUID.randomUUID().toString().replace("-", "")));

        SelfHostAddress.Resolution r = resolveUrl();
        resolution = r;
        if (!r.routable()) {
            logger.warning("Self-host URL is not reachable from the internet: " + r.advice());
        }
        return r.url();
    }

    /** Starts serving the pack staged by the last {@link #upload} at the public path. */
    @Override
    public void commit() {
        Pending p = pending.getAndSet(null);
        if (p == null) return;
        PackHttpHandler.ServedPack served = p.pack();
        PackHttpHandler.ServedPack old = current.getAndSet(served);
        if (old != null && !old.file().equals(served.file())) {
            PackHttpHandler.ServedPack older = previous.getAndSet(old);
            if (older != null && !older.file().equals(served.file()) && !older.file().equals(old.file())) {
                deleteQuietly(older.file());
            }
        }
        pruneSnapshots(served.file().getParentFile());
    }

    /** Resolves the advertised URL through the host chain (may run the opt-in IP lookup). */
    private SelfHostAddress.Resolution resolveUrl() {
        ConfigManager config = plugin.getConfigManager();
        List<SelfHostAddress.Candidate> candidates = new ArrayList<>();
        candidates.add(new SelfHostAddress.Candidate(SelfHostAddress.Source.PUBLIC_HOST, config.getSelfHostPublicHost()));
        if (config.isSelfHostDetectPublicIp() && config.getSelfHostPublicUrl().isEmpty()
                && config.getSelfHostPublicHost().isEmpty()) {
            candidates.add(new SelfHostAddress.Candidate(SelfHostAddress.Source.DETECTED_PUBLIC_IP, detectPublicIp()));
        }
        candidates.add(new SelfHostAddress.Candidate(SelfHostAddress.Source.SERVER_IP,
                ConfigManager.readServerProperties().getProperty("server-ip", "")));
        // The HTTP server binds the wildcard address; SelfHostAddress advertises it as localhost.
        candidates.add(new SelfHostAddress.Candidate(SelfHostAddress.Source.BIND_ADDRESS, "0.0.0.0"));
        return SelfHostAddress.resolve(config.getSelfHostPublicUrl(), candidates, boundPort);
    }

    private String detectPublicIp() {
        if (!detectionDone) {
            List<String> services = plugin.getConfigManager().getSelfHostPublicIpServices();
            logger.info("Looking up this server's public IP for the self-host URL (upload.self-host.detect-public-ip)"
                    + " via " + services);
            detectedIp = PublicIpDetector.detect(services, Duration.ofSeconds(5),
                    msg -> logger.warning("Public IP lookup: " + msg));
            detectionDone = true;
            if (detectedIp != null) logger.info("Detected public IP " + detectedIp);
        }
        return detectedIp;
    }

    /**
     * Verifies the URL {@link #upload} just returned: first through {@code 127.0.0.1}, then
     * through the advertised URL, each by comparing the first KiB with the served pack.
     *
     * @return {@code null} if players can use the URL, otherwise why not (so
     *         {@code upload.fallback} can take over). A failing advertised-URL check on an
     *         explicitly configured public-url is only warned about (hairpin checks can give
     *         false negatives behind some NATs/CDNs) and does not trigger the fallback.
     */
    @Override
    public String check(String url) {
        SelfHostAddress.Resolution r = resolution;
        boolean explicitUrl = r != null && r.source() == SelfHostAddress.Source.PUBLIC_URL;
        String routeProblem = r != null && !r.routable() && !explicitUrl ? r.advice() : null;

        Pending staged = pending.get();
        if (!plugin.getConfigManager().isSelfHostVerify()) {
            verification = new Verification(VerificationState.SKIPPED,
                    "upload.self-host.verify is false", Instant.now());
            return routeProblem;
        }
        if (staged == null) return routeProblem;
        File expected = staged.pack().file();

        String localUrl = "http://127.0.0.1:" + boundPort + SelfHostAddress.PACK_PATH;
        ReachabilityProbe.Result local = untokened(ReachabilityProbe.probe(withToken(localUrl, staged.token()),
                expected, PROBE_TIMEOUT), localUrl, staged.token());
        if (!local.ok()) {
            String reason = "local check failed: " + local.detail();
            verification = new Verification(VerificationState.FAILED, reason, Instant.now());
            logger.warning("Self-host verification failed, the HTTP server does not serve the pack: " + reason);
            return reason;
        }
        if (url.equals(localUrl)) {
            verification = new Verification(VerificationState.PASSED, "served on " + localUrl, Instant.now());
            return routeProblem;
        }
        ReachabilityProbe.Result advertised = untokened(ReachabilityProbe.probe(withToken(url, staged.token()),
                expected, PROBE_TIMEOUT), url, staged.token());
        if (!advertised.ok()) {
            String reason = "advertised URL check failed: " + advertised.detail();
            verification = new Verification(VerificationState.FAILED, reason, Instant.now());
            logger.warning("Self-host verification failed: the pack is served locally, but " + url
                    + " does not deliver it from this machine (" + advertised.detail() + "). Players may be"
                    + " unable to download it: check the port forward/firewall for port " + boundPort
                    + ", NAT hairpinning, or set upload.self-host.public-url to a URL that reaches this server.");
            return explicitUrl ? null : reason;
        }
        verification = new Verification(VerificationState.PASSED,
                "local and advertised URL serve the pack", Instant.now());
        return routeProblem;
    }

    /** Appends the verification token as a query parameter (before any fragment). */
    static String withToken(String url, String token) {
        int hash = url.indexOf('#');
        String base = hash >= 0 ? url.substring(0, hash) : url;
        String fragment = hash >= 0 ? url.substring(hash) : "";
        return base + (base.contains("?") ? "&" : "?") + VERIFY_PARAM + "=" + token + fragment;
    }

    /** Shows the plain URL (without the one-off token) in a probe result. */
    private static ReachabilityProbe.Result untokened(ReachabilityProbe.Result r, String url, String token) {
        return new ReachabilityProbe.Result(r.ok(), r.detail().replace(withToken(url, token), url));
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
        PackHttpHandler.ServedPack cur = current.get();
        PackHttpHandler.ServedPack prev = previous.get();
        Pending staged = pending.get();
        for (File f : files) {
            if (cur != null && f.equals(cur.file())) continue;
            if (prev != null && f.equals(prev.file())) continue;
            if (staged != null && f.equals(staged.pack().file())) continue;
            if (f.getName().endsWith(".tmp")) continue;
            deleteQuietly(f);
        }
    }

    /**
     * Deletes a snapshot; failures (e.g. Windows refusing to delete a file that is being
     * downloaded) are harmless, the next upload retries.
     */
    private void deleteQuietly(File f) {
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException e) {
            logger.debug("Could not delete old self-host snapshot " + f.getName() + ": " + e.getMessage());
        }
    }
}
