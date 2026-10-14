package sh.pcx.packmerger.bungee;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import sh.pcx.packmerger.common.PackInfo;
import sh.pcx.packmerger.common.PackMessaging;
import sh.pcx.packmerger.common.proxy.ProxyCoordinator;
import sh.pcx.packmerger.common.proxy.ProxySettings;

import java.util.concurrent.TimeUnit;

/**
 * BungeeCord proxy companion to PackMerger.
 *
 * <p>BungeeCord has no resource-pack API, so the proxy can't send the pack itself.
 * Instead it decides <em>which</em> pack a player should have and <em>when</em> (once per
 * session, again after an update, per backend in {@code mode=per-server}) and asks the
 * PackMerger on the backend the player is on to send it, with a signed
 * {@link sh.pcx.packmerger.common.PackMessage.Kind#OFFER OFFER} message. The backend
 * sends the pack under the proxy's pack id, so it replaces itself in place on update and
 * server switch. This needs the shared {@code secret} (backends refuse unsigned offers)
 * and PackMerger on the backends players connect to.</p>
 *
 * <p>Everything else (state persistence, signatures, replay protection, accept-from) is
 * the same {@link ProxyCoordinator} the Velocity plugin uses.</p>
 */
public final class PackMergerBungee extends Plugin implements Listener {

    /** Delay before asking the backend to send the pack, so the player has finished joining it. */
    private static final long OFFER_DELAY_MILLIS = 1000L;

    private volatile ProxyCoordinator coordinator;
    private volatile boolean warnedNoSecret;

    @Override
    public void onEnable() {
        getProxy().registerChannel(PackMessaging.CHANNEL);
        ProxySettings settings = ProxySettings.load(getDataFolder().toPath(),
                w -> getLogger().warning("PackMerger proxy: " + w));
        ProxyCoordinator c = new ProxyCoordinator(settings, getDataFolder().toPath(), new ProxyCoordinator.Log() {
            @Override public void info(String message) { getLogger().info("PackMerger proxy: " + message); }
            @Override public void warn(String message) { getLogger().warning("PackMerger proxy: " + message); }
        });
        c.start();
        this.coordinator = c;
        getProxy().getPluginManager().registerListener(this, this);

        String version = getDescription().getVersion();
        if (!settings.hasSecret()) {
            getLogger().severe("PackMerger proxy " + version + ": no 'secret' set in plugins/PackMerger/"
                    + ProxySettings.FILE_NAME + ". BungeeCord can't send resource packs itself, so this plugin "
                    + "asks the backend to send them, and backends only accept signed requests: players get "
                    + "no pack from the proxy until the same secret is set here and as "
                    + "distribution.proxy-secret on the backends.");
        }
        PackInfo pack = c.networkPack();
        if (settings.mode() == ProxySettings.Mode.PER_SERVER) {
            getLogger().info("PackMerger proxy " + version + " ready (per-server mode): each backend's own pack "
                    + "is sent when a player connects to it");
        } else if (pack != null) {
            getLogger().info("PackMerger proxy " + version + " ready: offering " + pack.url()
                    + " (from " + c.networkSource() + ")");
        } else {
            getLogger().warning("PackMerger proxy " + version + ": no pack yet. Set 'url' in plugins/PackMerger/"
                    + ProxySettings.FILE_NAME + " or enable distribution.proxy-notify on a backend.");
        }
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(PackMessaging.CHANNEL);
    }

    /** Fires once the player is on a backend: on first connect and on every switch. */
    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        ProxiedPlayer player = event.getPlayer();
        Server server = player.getServer();
        if (server == null) return;
        String name = server.getInfo().getName();
        getProxy().getScheduler().schedule(this, () -> {
            Server current = player.getServer();
            if (!player.isConnected() || current == null || !current.getInfo().getName().equals(name)) return;
            deliver(player, current);
        }, OFFER_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        ProxyCoordinator c = coordinator;
        if (c != null) c.forget(event.getPlayer().getUniqueId());
    }

    /** Receives pack announcements from backends over the plugin channel. */
    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!PackMessaging.CHANNEL.equals(event.getTag())) return;
        // Never forward our channel: not to clients (backend announcements), and not to
        // backends (a client must not be able to pose as the proxy).
        event.setCancelled(true);
        if (!(event.getSender() instanceof Server server)) return;
        ProxyCoordinator c = coordinator;
        if (c == null) return;

        ProxyCoordinator.Result result = c.onBackendMessage(server.getInfo().getName(), event.getData());
        if (!result.accepted()) return; // the coordinator logged why
        if (result.changed()) {
            getLogger().info("PackMerger proxy: " + result.detail());
            if (c.settings().resendOnUpdate()) {
                for (ProxiedPlayer player : getProxy().getPlayers()) {
                    Server current = player.getServer();
                    if (current != null) deliver(player, current);
                }
            }
        }
    }

    /** Asks the backend {@code server} to send {@code player} the pack they should have, if any. */
    private void deliver(ProxiedPlayer player, Server server) {
        ProxyCoordinator c = coordinator;
        if (c == null) return;
        if (!c.settings().hasSecret()) {
            if (!warnedNoSecret) {
                warnedNoSecret = true;
                getLogger().warning("PackMerger proxy: not sending the pack to players, because no secret is set "
                        + "(see the startup message)");
            }
            return;
        }
        String serverName = server.getInfo().getName();
        PackInfo pack = c.nextOffer(player.getUniqueId(), serverName);
        if (pack == null) return;
        byte[] payload = c.offerPayload(player.getUniqueId(), serverName, pack);
        if (payload != null) server.sendData(PackMessaging.CHANNEL, payload);
    }
}
