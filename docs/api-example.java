// Minimal example of using PackMerger's API and Bukkit events from another plugin.
//
// Drop this into your plugin's own source tree. It's not compiled as part of PackMerger —
// it lives under docs/, outside any source set — as a copy-paste starting point.
//
// 1. plugin.yml:   depend: [PackMerger]        (or softdepend: [PackMerger])
// 2. build.gradle: compileOnly files('libs/packmerger-plugin-<version>.jar')
//                  (or 'com.github.pcxgg:PackMerger:<version>' via JitPack)
//
// The API types (sh.pcx.packmerger.api.*) are shared with other plugins; everything else
// in the PackMerger jar is internal and loaded in its own classloader, so only use the API.

package com.example.myplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import sh.pcx.packmerger.api.PackMergerApi;
import sh.pcx.packmerger.api.PackOptions;
import sh.pcx.packmerger.api.events.PackMergedEvent;
import sh.pcx.packmerger.api.events.PackMergerCollectPacksEvent;
import sh.pcx.packmerger.api.events.PackUploadedEvent;
import sh.pcx.packmerger.api.events.PackValidationFailedEvent;

import java.net.URI;
import java.nio.file.Path;

public class MyPlugin extends JavaPlugin implements Listener {

    private PackMergerApi packs;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("PackMerger") == null) {
            getLogger().warning("PackMerger not installed — integration disabled");
            return;
        }
        packs = PackMergerApi.get();
        getServer().getPluginManager().registerEvents(this, this);

        // Contribute our resource pack (a folder or a zip). PackMerger stages it into
        // packs/.api-packs/myplugin.zip before every merge and merges it with the
        // server's other packs. Operators place it with "myplugin" in priority:.
        // It is unregistered automatically when this plugin is disabled.
        Path pack = getDataFolder().toPath().resolve("resource_pack");
        packs.registerPack(this, "myplugin", pack);

        // A folder holding several packs, unioned into one:
        // packs.registerPack(this, "myplugin-addons", getDataFolder().toPath().resolve("addons"),
        //         PackOptions.defaults().withCluster(true));

        // Or a pack you publish online (https; downloaded with ETag on startup and /pm reload):
        // packs.registerPack(this, "myplugin-cdn", URI.create("https://cdn.example.com/pack.zip"));

        getLogger().info("Current pack: " + packs.getCurrentPackUrl()
                + " (sha1=" + packs.getCurrentPackSha1Hex() + ")");
    }

    // Rebuilt your pack? Ask for a merge: the registered folder/zip is re-read (and only
    // copied once it has stopped changing) at the start of every merge.
    public void onPackRebuilt() {
        packs.triggerMerge().thenRun(() ->
                getLogger().info("Merge cycle complete; pack URL is " + packs.getCurrentPackUrl()));
    }

    // Alternative to registering in onEnable: contribute only when a merge runs.
    @EventHandler
    public void onCollect(PackMergerCollectPacksEvent e) {
        // e.registerPack(this, "myplugin-lazy", buildPackNow());
    }

    @EventHandler
    public void onPackMerged(PackMergedEvent e) {
        // Fired after merge + validate, before upload (asynchronously).
        getLogger().info("Pack merged: " + e.getOutputFile().getName()
                + " (" + e.getValidationResult().errors() + " errors, "
                + e.getValidationResult().warnings() + " warnings)");
        // Provenance: who contributed which file?
        e.getProvenance().filesByWinner().forEach((pack, count) ->
                getLogger().info("  " + pack + ": " + count + " files won"));
    }

    @EventHandler
    public void onPackUploaded(PackUploadedEvent e) {
        // The public URL is live: post it to Discord, update a sign, etc.
        getLogger().info("Pack uploaded: " + e.getUrl());
    }

    @EventHandler
    public void onValidationFailed(PackValidationFailedEvent e) {
        if (e.wasRolledBack()) {
            getLogger().severe("PackMerger rolled back to the previous pack: "
                    + e.getResult().errors() + " validation errors");
        } else {
            getLogger().severe("PackMerger shipped a pack with validation errors (nothing to roll back to)");
        }
    }

    @Override
    public void onDisable() {
        // Not required (PackMerger drops our packs when we are disabled), but explicit is fine:
        if (packs != null) packs.unregisterPack("myplugin");
    }
}
