package sh.pcx.packmerger.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import sh.pcx.packmerger.PackMergerBootstrap;

/** Unregisters the API packs of a plugin when it is disabled. */
public class PluginDisableListener implements Listener {

    private final PackMergerBootstrap plugin;

    public PluginDisableListener(PackMergerBootstrap plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        plugin.onPluginDisabled(event.getPlugin());
    }
}
