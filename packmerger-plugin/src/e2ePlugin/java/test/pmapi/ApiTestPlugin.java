package test.pmapi;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import sh.pcx.packmerger.api.PackMergerApi;
import sh.pcx.packmerger.api.RegisteredPack;
import sh.pcx.packmerger.api.events.PackMergerCollectPacksEvent;
import sh.pcx.packmerger.api.events.PackUploadedEvent;

/**
 * E2E-only plugin (never shipped): uses PackMerger's public API exactly as a third-party
 * plugin would, from its own classloader. Driven over RCON with /pmapitest.
 */
public class ApiTestPlugin extends JavaPlugin implements Listener {

    private PackMergerApi api;
    private volatile boolean lazy;

    @Override
    public void onEnable() {
        api = PackMergerApi.get();
        getLogger().info("[pmapitest] found PackMerger API: " + (api != null));
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onUploaded(PackUploadedEvent e) {
        getLogger().info("[pmapitest] PackUploadedEvent received: " + e.getUrl());
    }

    @EventHandler
    public void onCollect(PackMergerCollectPacksEvent e) {
        getLogger().info("[pmapitest] PackMergerCollectPacksEvent received (lazy=" + lazy + ")");
        if (lazy) e.registerPack(this, "api-lazy", getDataFolder().toPath().resolve("lazy"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0];
        try {
            switch (sub) {
                case "register" -> {
                    api.registerPack(this, "api-test", getDataFolder().toPath().resolve("pack"));
                    sender.sendMessage("pmapitest: registered api-test");
                }
                case "steal" -> {
                    // Another owner's alias must be refused.
                    api.registerPack(getServer().getPluginManager().getPlugin("PackMerger"), "api-test",
                            getDataFolder().toPath().resolve("pack"));
                    sender.sendMessage("pmapitest: steal unexpectedly succeeded");
                }
                case "unregister" -> sender.sendMessage("pmapitest: unregistered=" + api.unregisterPack("api-test")
                        + "," + api.unregisterPack("api-lazy"));
                case "lazy" -> {
                    lazy = args.length > 1 && args[1].equals("on");
                    sender.sendMessage("pmapitest: lazy=" + lazy);
                }
                case "disable" -> {
                    sender.sendMessage("pmapitest: disabling");
                    getServer().getPluginManager().disablePlugin(this);
                }
                default -> {
                    StringBuilder sb = new StringBuilder("pmapitest: registered=[");
                    for (RegisteredPack p : api.getRegisteredPacks()) {
                        sb.append(p.alias()).append(" by ").append(p.owner()).append(';');
                    }
                    sender.sendMessage(sb.append(']').toString());
                }
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage("pmapitest: refused: " + e.getMessage());
        }
        return true;
    }
}
