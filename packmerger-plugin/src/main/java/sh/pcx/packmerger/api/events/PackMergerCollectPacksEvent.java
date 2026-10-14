package sh.pcx.packmerger.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import sh.pcx.packmerger.api.PackMergerApi;
import sh.pcx.packmerger.api.PackOptions;

import java.net.URI;
import java.nio.file.Path;

/**
 * Fired asynchronously on the merge thread at the start of every merge, before packs
 * are staged. Plugins that build their pack lazily (or only want to contribute when a
 * merge actually happens) can register it here.
 *
 * <p>{@link #registerPack} is {@link PackMergerApi#registerPack} without scheduling
 * another merge: the registration is included in the merge that fired this event, and
 * it stays registered (like any other) until it is unregistered or its owner is
 * disabled.</p>
 */
public class PackMergerCollectPacksEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PackMergerApi api;

    public PackMergerCollectPacksEvent(PackMergerApi api) {
        super(true);
        this.api = api;
    }

    /** @return the API, for queries and {@link PackMergerApi#unregisterPack} */
    public PackMergerApi getApi() { return api; }

    /** Registers a folder or zip pack for this and later merges. */
    public void registerPack(Plugin owner, String alias, Path source) {
        registerPack(owner, alias, source, PackOptions.defaults());
    }

    /** Registers a folder or zip pack for this and later merges. */
    public void registerPack(Plugin owner, String alias, Path source, PackOptions options) {
        api.registerPack(owner, alias, source, noMerge(options));
    }

    /** Registers a downloaded pack for this and later merges. */
    public void registerPack(Plugin owner, String alias, URI url) {
        registerPack(owner, alias, url, PackOptions.defaults());
    }

    /** Registers a downloaded pack for this and later merges. */
    public void registerPack(Plugin owner, String alias, URI url, PackOptions options) {
        api.registerPack(owner, alias, url, noMerge(options));
    }

    private static PackOptions noMerge(PackOptions options) {
        return (options == null ? PackOptions.defaults() : options).withMergeNow(false);
    }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
