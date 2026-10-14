package sh.pcx.packmerger.remote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches {@link RemoteSpec remote packs} over HTTP(S) into the local
 * {@code packs/.remote-cache/} directory so the existing merge pipeline can
 * consume them by their alias.
 *
 * <p>Caching uses {@code ETag} / {@code Last-Modified} headers stored in a
 * sidecar JSON next to each cached zip, so repeat fetches against unchanged
 * origins return {@code 304 Not Modified} and skip the download. If the origin
 * is unreachable but a cached copy exists, the cache is preserved and used
 * for this merge — a transient network blip doesn't take the pack offline.</p>
 *
 * <p>Uses the JDK's {@link HttpClient}; no new runtime dependencies. All fetches
 * run synchronously off the main thread (the caller decides scheduling).</p>
 */
public class RemotePackManager {

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)\\}");

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;
    private final HttpClient client;
    private final File cacheDir;

    public RemotePackManager(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.cacheDir = new File(plugin.getPacksFolder(), ".remote-cache");
    }

    /** @return {@code packs/.remote-cache/} — created on demand */
    public File cacheDir() {
        if (!cacheDir.exists()) cacheDir.mkdirs();
        return cacheDir;
    }

    /**
     * @return the canonical cache file for the given alias
     *         ({@code packs/.remote-cache/<alias>.zip})
     */
    public File cacheFile(String alias) {
        return new File(cacheDir(), alias + ".zip");
    }

    public enum Trigger { STARTUP, RELOAD, MANUAL }

    /**
     * Fetches every remote spec whose refresh policy matches the given trigger.
     */
    public List<FetchResult> fetchAll(List<RemoteSpec> specs, Trigger trigger) {
        List<FetchResult> out = new ArrayList<>();
        for (RemoteSpec spec : specs) {
            boolean eligible = switch (trigger) {
                case STARTUP -> spec.shouldFetchOnStartup();
                case RELOAD -> spec.shouldFetchOnStartup() || spec.shouldFetchOnReload();
                case MANUAL -> true;
            };
            if (!eligible) {
                out.add(new FetchResult(spec.alias(), FetchResult.Status.SKIPPED_BY_POLICY,
                        "refresh policy " + spec.refresh() + " does not apply on " + trigger));
                continue;
            }
            out.add(fetch(spec));
        }
        return out;
    }

    /**
     * Fetches a single remote spec, honoring cached ETag/Last-Modified so we
     * return early when the origin hasn't changed.
     */
    public FetchResult fetch(RemoteSpec spec) {
        return fetchInto(spec, cacheDir());
    }

    /**
     * Same as {@link #fetch(RemoteSpec)} but downloads into {@code dir/<alias>.zip}
     * (with its {@code <alias>.meta.json} sidecar). Used by plugin-pack discovery to
     * stage URL-published plugin packs into {@code packs/.plugin-packs/}.
     */
    public FetchResult fetchInto(RemoteSpec spec, File dir) {
        dir.mkdirs();
        String resolvedUrl = substituteEnv(spec.url());
        File cacheFile = new File(dir, spec.alias() + ".zip");
        File metaFile = new File(dir, spec.alias() + ".meta.json");

        // HTTPS guard — overridden only when allow-http is explicitly set
        if (resolvedUrl.startsWith("http://") && !spec.allowHttp()) {
            String msg = "refusing plain-HTTP URL (set allow-http: true to opt in): " + resolvedUrl;
            logger.warning("[remote] " + msg);
            return new FetchResult(spec.alias(),
                    cacheFile.isFile() ? FetchResult.Status.ERROR_USING_CACHE : FetchResult.Status.ERROR_NO_CACHE, msg);
        }

        CachedMeta meta = loadMeta(metaFile);

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(resolvedUrl))
                .timeout(Duration.ofMinutes(5))
                .GET();
        applyAuth(req, spec.auth());
        if (meta != null && meta.etag() != null) req.header("If-None-Match", meta.etag());
        if (meta != null && meta.lastModified() != null) req.header("If-Modified-Since", meta.lastModified());

        try {
            HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code == 304) {
                logger.remote("not modified: " + spec.alias() + " (using cache)");
                return new FetchResult(spec.alias(), FetchResult.Status.NOT_MODIFIED, "304 Not Modified");
            }
            if (code < 200 || code >= 300) {
                String msg = "HTTP " + code + " from " + resolvedUrl;
                logger.warning("[remote] " + spec.alias() + ": " + msg);
                return new FetchResult(spec.alias(),
                        cacheFile.isFile() ? FetchResult.Status.ERROR_USING_CACHE : FetchResult.Status.ERROR_NO_CACHE,
                        msg);
            }

            // Write to a temp file then atomic-rename so a torn download doesn't corrupt cache
            File tmp = new File(dir, spec.alias() + ".zip.part");
            Files.write(tmp.toPath(), resp.body());
            Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Capture conditional-request headers for next time
            CachedMeta newMeta = new CachedMeta(
                    resp.headers().firstValue("ETag").orElse(null),
                    resp.headers().firstValue("Last-Modified").orElse(null),
                    Instant.now().toString());
            saveMeta(metaFile, newMeta);

            logger.remote("fetched " + spec.alias() + ": " + resp.body().length + " bytes");
            return new FetchResult(spec.alias(), FetchResult.Status.FETCHED,
                    resp.body().length + " bytes");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            logger.warning("[remote] " + spec.alias() + ": " + msg);
            return new FetchResult(spec.alias(),
                    cacheFile.isFile() ? FetchResult.Status.ERROR_USING_CACHE : FetchResult.Status.ERROR_NO_CACHE,
                    msg);
        }
    }

    /**
     * Deletes cached downloads in {@code packs/.remote-cache/} whose alias is no
     * longer declared under {@code remote-packs:}, so a removed remote pack stops
     * being merged.
     *
     * @return the aliases whose cached zip was deleted
     */
    public List<String> pruneUndeclared(List<RemoteSpec> declared) {
        if (!cacheDir.isDirectory()) return List.of();
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (RemoteSpec spec : declared) keep.add(spec.alias());
        List<String> pruned = sh.pcx.packmerger.discovery.PluginPackStager.prune(cacheDir, keep);
        for (String alias : pruned) {
            logger.remote("removed cached pack '" + alias + "' (no longer declared in remote-packs)");
        }
        return pruned;
    }

    private void applyAuth(HttpRequest.Builder req, RemoteSpec.AuthSpec auth) {
        if (auth == null || auth.isNone()) return;
        switch (auth.type().toLowerCase()) {
            case "bearer" -> {
                String token = substituteEnv(auth.token());
                if (token != null && !token.isEmpty()) req.header("Authorization", "Bearer " + token);
            }
            case "basic" -> {
                String user = substituteEnv(auth.username());
                String pass = substituteEnv(auth.password());
                if (user != null && pass != null) {
                    String credentials = user + ":" + pass;
                    String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    req.header("Authorization", "Basic " + encoded);
                }
            }
            default -> logger.warning("[remote] unknown auth type '" + auth.type() + "' — sending unauthenticated");
        }
    }

    /**
     * Replaces {@code ${NAME}} placeholders with the corresponding environment
     * variable, or logs a warning if the variable is unset. Pass-through for
     * strings without placeholders. Visible-for-testing.
     */
    public static String substituteEnv(String raw) {
        if (raw == null) return null;
        Matcher m = ENV_VAR.matcher(raw);
        if (!m.find()) return raw;
        m.reset();
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            String value = System.getenv(var);
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Record stored next to each cached zip capturing the conditional-request
     * headers the origin returned last time. Plain Gson JSON, not versioned —
     * we can break the format at any time because it's regenerated on every fetch.
     */
    private record CachedMeta(String etag, String lastModified, String fetchedAt) {}

    private CachedMeta loadMeta(File f) {
        if (!f.isFile()) return null;
        try {
            String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return new CachedMeta(
                    obj.has("etag") && !obj.get("etag").isJsonNull() ? obj.get("etag").getAsString() : null,
                    obj.has("lastModified") && !obj.get("lastModified").isJsonNull() ? obj.get("lastModified").getAsString() : null,
                    obj.has("fetchedAt") && !obj.get("fetchedAt").isJsonNull() ? obj.get("fetchedAt").getAsString() : null);
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    private void saveMeta(File f, CachedMeta meta) {
        JsonObject obj = new JsonObject();
        obj.addProperty("etag", meta.etag());
        obj.addProperty("lastModified", meta.lastModified());
        obj.addProperty("fetchedAt", meta.fetchedAt());
        try {
            Files.writeString(f.toPath(), obj.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.debug("[remote] could not save cache meta for " + f.getName() + ": " + e.getMessage());
        }
    }

    /** Helper for tests. */
    static Map<String, String> parseMeta(Path metaFile) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(metaFile)) return out;
        String json = Files.readString(metaFile, StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        obj.entrySet().forEach(e -> out.put(e.getKey(),
                e.getValue().isJsonNull() ? null : e.getValue().getAsString()));
        return out;
    }
}
