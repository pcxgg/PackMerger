package sh.pcx.packmerger.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls a {@code versions.json} manifest hosted in the PackMerger GitHub repo
 * on plugin enable and notifies admins when a newer release is available.
 *
 * <p>The manifest format is intentionally minimal:</p>
 * <pre>
 * {
 *   "latest": "1.3.0",
 *   "latest_url": "https://www.spigotmc.org/resources/packmerger.132700/updates",
 *   "latest_notes": "Auto plugin-pack discovery, Bedrock/Geyser, Velocity proxy, Paper 26.1.2"
 * }
 * </pre>
 *
 * <p>Admins with the {@code packmerger.admin} permission see a chat notice on
 * join if an update is available; the console also logs at enable time.
 * Everything is advisory — the plugin never auto-downloads or prompts.</p>
 */
public class UpdateChecker {

    /** Default manifest location in the PackMerger repo's default branch. */
    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/PCX-Network/PackMerger/master/versions.json";

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;
    private final HttpClient client;
    private final AtomicReference<Result> latestResult = new AtomicReference<>();

    public UpdateChecker(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches the manifest, compares against the running version, and caches
     * the result. Safe to call repeatedly; the last result is always available
     * via {@link #getLatestResult()}.
     */
    public void checkNow(String manifestUrl, String runningVersion) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(manifestUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "PackMerger-UpdateChecker/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                logger.debug("[update] manifest returned HTTP " + resp.statusCode() + "; skipping");
                return;
            }

            JsonElement parsed = JsonParser.parseString(resp.body());
            if (!parsed.isJsonObject()) {
                logger.debug("[update] manifest is not a JSON object; skipping");
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            String latest = root.has("latest") && !root.get("latest").isJsonNull()
                    ? root.get("latest").getAsString() : null;
            if (latest == null) {
                logger.debug("[update] manifest missing 'latest' field; skipping");
                return;
            }
            String latestUrl = root.has("latest_url") && !root.get("latest_url").isJsonNull()
                    ? root.get("latest_url").getAsString() : null;
            String notes = root.has("latest_notes") && !root.get("latest_notes").isJsonNull()
                    ? root.get("latest_notes").getAsString() : null;

            Result result = new Result(runningVersion, latest, latestUrl, notes,
                    compareVersions(runningVersion, latest) < 0);
            latestResult.set(result);

            if (result.updateAvailable()) {
                logger.warning("[update] newer PackMerger release available: "
                        + result.latest() + " (running " + runningVersion + ")");
                if (latestUrl != null) logger.warning("[update] release: " + latestUrl);
            } else {
                logger.info("[update] PackMerger is up to date (" + runningVersion + ")");
            }
        } catch (Exception e) {
            logger.debug("[update] check failed: " + e.getMessage());
        }
    }

    /** @return the most recent check's result, or {@code null} if none completed */
    public Result getLatestResult() { return latestResult.get(); }

    /**
     * @return a one-line advisory message describing the available update, or
     *         {@code null} if no update is currently known. Callers render it
     *         into whatever chat API they have available (Adventure Component,
     *         legacy String, etc.) so this class stays free of Paper/Bukkit
     *         imports and remains unit-testable in isolation.
     */
    public String updateMessageOrNull() {
        Result r = latestResult.get();
        if (r == null || !r.updateAvailable()) return null;
        return "[PackMerger] Update available: " + r.running() + " → " + r.latest()
                + (r.url() != null ? " — " + r.url() : "");
    }

    /**
     * Result of a version check. Fields are all strings so the caller can
     * display them verbatim without re-parsing.
     *
     * @param running          version currently running (from plugin.yml)
     * @param latest           version declared in the manifest
     * @param url              optional release URL from the manifest
     * @param notes            optional release notes from the manifest
     * @param updateAvailable  {@code true} if {@code latest} sorts higher than {@code running}
     */
    public record Result(String running, String latest, String url, String notes, boolean updateAvailable) {}

    /**
     * Semantic version comparison: dot-separated numeric components, missing
     * trailing components treated as zero, non-numeric components compared
     * lexically as a last-resort tiebreaker.
     */
    static int compareVersions(String a, String b) {
        if (Objects.equals(a, b)) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        String[] ap = a.split("[.-]");
        String[] bp = b.split("[.-]");
        int max = Math.max(ap.length, bp.length);
        for (int i = 0; i < max; i++) {
            String ai = i < ap.length ? ap[i] : "0";
            String bi = i < bp.length ? bp[i] : "0";
            Integer an = tryInt(ai);
            Integer bn = tryInt(bi);
            if (an != null && bn != null) {
                int cmp = Integer.compare(an, bn);
                if (cmp != 0) return cmp;
            } else {
                int cmp = ai.compareTo(bi);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private static Integer tryInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
