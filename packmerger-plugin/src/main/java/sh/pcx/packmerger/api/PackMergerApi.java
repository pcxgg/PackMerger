package sh.pcx.packmerger.api;

import sh.pcx.packmerger.merge.MergeProvenance;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * Stable, third-party-facing API surface for PackMerger.
 *
 * <p>Other plugins can obtain the API instance via
 * {@code ((PackMerger) Bukkit.getPluginManager().getPlugin("PackMerger")).getApi()}
 * and use it to query the current merged-pack state or trigger merges
 * programmatically, without coupling to PackMerger's internals.</p>
 *
 * <p>For event-driven integrations (e.g. reacting to a new merge), prefer the
 * Bukkit events under {@link sh.pcx.packmerger.api.events} — they carry richer
 * context and avoid polling.</p>
 *
 * <p><b>Experimental in 1.1.0.</b> This API surface may change in 1.1.x
 * patch releases as we learn how consumers use it. Targeted as stable in 1.2.</p>
 */
public interface PackMergerApi {

    /**
     * @return the public download URL of the pack currently offered to players, or
     *         {@code null} if nothing has been published yet. Always consistent with
     *         {@link #getCurrentPackSha1Hex()}: both change together, after an upload
     *         succeeded.
     */
    String getCurrentPackUrl();

    /**
     * @return the hex-encoded SHA-1 hash of the pack currently offered to players,
     *         or {@code null} if nothing has been published yet
     */
    String getCurrentPackSha1Hex();

    /**
     * @return timestamp of the most recent successful merge, or {@code null} if none
     */
    LocalDateTime getLastMergeTime();

    /**
     * Per-file record of which packs contributed to the last merge's output.
     * Powers {@code /pm inspect} and is the preferred way to answer
     * "which pack is this texture from?" programmatically.
     *
     * @return provenance of the last merge, or {@code null} if none (and no
     *         prior {@code .merge-provenance.json} was on disk at startup)
     */
    MergeProvenance getLastMergeProvenance();

    /**
     * Triggers a full merge-validate-upload cycle asynchronously. Returns
     * immediately with a future that completes when the pipeline finishes
     * (including upload, if auto-upload is enabled).
     *
     * <p>If a merge is already in progress, the returned future completes
     * without starting a new merge.</p>
     *
     * @return a future signalling pipeline completion
     */
    CompletableFuture<Void> triggerMerge();

    /**
     * @return {@code true} if a merge is currently in progress
     */
    boolean isMerging();
}
