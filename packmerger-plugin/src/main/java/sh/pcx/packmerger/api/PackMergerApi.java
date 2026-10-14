package sh.pcx.packmerger.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import sh.pcx.packmerger.merge.MergeProvenance;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Stable, third-party-facing API surface for PackMerger.
 *
 * <p>Other plugins obtain the instance with {@link #get()} (or
 * {@code Bukkit.getServicesManager().load(PackMergerApi.class)}) after adding
 * {@code depend: [PackMerger]} or {@code softdepend: [PackMerger]} to their
 * {@code plugin.yml}. They can query the merged pack, trigger merges, and
 * contribute their own resource pack with {@link #registerPack}.</p>
 *
 * <p>For event-driven integrations (e.g. reacting to a new merge), prefer the
 * Bukkit events under {@link sh.pcx.packmerger.api.events} — they carry richer
 * context and avoid polling.</p>
 *
 * <p>Methods are thread-safe and may be called from any thread.</p>
 */
public interface PackMergerApi {

    /**
     * @return the running PackMerger API
     * @throws IllegalStateException if PackMerger is not installed or not enabled
     */
    static PackMergerApi get() {
        PackMergerApi api = Bukkit.getServicesManager().load(PackMergerApi.class);
        if (api == null) throw new IllegalStateException("PackMerger is not installed or not enabled");
        return api;
    }

    /**
     * Adds a resource pack (a folder or a zip) owned by {@code owner} to every merge.
     *
     * <p>The pack is staged into {@code packs/.api-packs/<alias>.zip} before each merge
     * with the same rules as plugin-pack discovery: it must have {@code pack.mcmeta} or
     * {@code assets/} at its root (a zip wrapped in one folder is unwrapped), a folder
     * of several packs is unioned (see {@link PackOptions#cluster}), and a source that
     * is still being written is not copied until it is stable. Operators place it with
     * the alias in {@code priority:}; unlisted, it merges below the listed packs.</p>
     *
     * <p>Registrations last until {@link #unregisterPack} or until {@code owner} is
     * disabled. Registering an alias again (same owner) replaces it. Unless
     * {@link PackOptions#mergeNow} is {@code false}, a merge is scheduled (coalesced
     * with other registrations; the startup merge picks up registrations made while
     * plugins enable).</p>
     *
     * @param owner   the registering plugin
     * @param alias   lower-case name, {@code [a-z0-9][a-z0-9._-]*}, at most 64 characters
     * @param source  the pack folder or zip (absolute, or relative to the server folder)
     * @param options staging options, or {@code null} for {@link PackOptions#defaults()}
     * @throws IllegalArgumentException if the alias is invalid or owned by another plugin
     */
    void registerPack(Plugin owner, String alias, Path source, PackOptions options);

    /** {@link #registerPack(Plugin, String, Path, PackOptions)} with default options. */
    default void registerPack(Plugin owner, String alias, Path source) {
        registerPack(owner, alias, source, null);
    }

    /**
     * Adds a resource pack zip downloaded from {@code url} to every merge. It is
     * downloaded into {@code packs/.api-packs/} on registration, on startup and on
     * {@code /pm reload} (honouring ETag/Last-Modified); otherwise behaves like
     * {@link #registerPack(Plugin, String, Path, PackOptions)}. Plain {@code http}
     * URLs are refused unless {@link PackOptions#allowHttp} is set.
     *
     * @throws IllegalArgumentException if the alias is invalid or owned by another plugin,
     *                                  or the URL is not http(s)
     */
    void registerPack(Plugin owner, String alias, URI url, PackOptions options);

    /** {@link #registerPack(Plugin, String, URI, PackOptions)} with default options. */
    default void registerPack(Plugin owner, String alias, URI url) {
        registerPack(owner, alias, url, null);
    }

    /**
     * Removes a registered pack. Its staged copy is deleted on the next merge, which is
     * scheduled right away.
     *
     * @return {@code true} if the alias was registered
     */
    boolean unregisterPack(String alias);

    /** @return the packs currently registered through this API, in registration order */
    List<RegisteredPack> getRegisteredPacks();

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
