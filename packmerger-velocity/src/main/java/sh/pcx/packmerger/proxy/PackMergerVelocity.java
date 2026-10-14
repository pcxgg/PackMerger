package sh.pcx.packmerger.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import sh.pcx.packmerger.common.Hex;
import sh.pcx.packmerger.common.PackInfo;
import sh.pcx.packmerger.common.PackMessaging;
import sh.pcx.packmerger.common.proxy.ProxyCoordinator;
import sh.pcx.packmerger.common.proxy.ProxySettings;

import java.nio.file.Path;

/**
 * Velocity proxy companion to PackMerger. Offers the merged resource pack from the proxy,
 * network-wide (one pack) or per backend ({@code mode=per-server}).
 *
 * <p>The pack comes from {@code config.properties} or from backends that push it over the
 * {@link PackMessaging#CHANNEL} channel ({@code distribution.proxy-notify}). Pushed packs
 * are remembered in {@code state.properties} across restarts, messages are authenticated
 * with a shared secret, updates are re-offered to online players in place, and each player
 * is offered a given pack once per session. The logic lives in {@link ProxyCoordinator},
 * shared with the BungeeCord plugin.</p>
 */
@Plugin(
        id = "packmerger",
        name = "PackMerger",
        version = BuildConstants.VERSION,
        description = "Network-wide resource pack distribution for PackMerger",
        authors = {"PCX Network"}
)
public final class PackMergerVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(PackMessaging.CHANNEL);

    private volatile ProxyCoordinator coordinator;

    @Inject
    public PackMergerVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(channel);
        ProxySettings settings = ProxySettings.load(dataDir, w -> logger.warn("PackMerger proxy: {}", w));
        ProxyCoordinator c = new ProxyCoordinator(settings, dataDir, new ProxyCoordinator.Log() {
            @Override public void info(String message) { logger.info("PackMerger proxy: {}", message); }
            @Override public void warn(String message) { logger.warn("PackMerger proxy: {}", message); }
        });
        c.start();
        this.coordinator = c;

        PackInfo pack = c.networkPack();
        if (settings.mode() == ProxySettings.Mode.PER_SERVER) {
            logger.info("PackMerger proxy {} ready (per-server mode): offering each backend's own pack on connect",
                    BuildConstants.VERSION);
        } else if (pack != null) {
            logger.info("PackMerger proxy {} ready: offering {} (from {})", BuildConstants.VERSION,
                    pack.url(), c.networkSource());
        } else {
            logger.warn("PackMerger proxy {}: no pack yet. Set 'url' in plugins/packmerger/config.properties "
                    + "or enable distribution.proxy-notify on a backend.", BuildConstants.VERSION);
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        ProxyCoordinator c = coordinator;
        if (c == null) return;
        Player player = event.getPlayer();
        offer(player, c.nextOffer(player.getUniqueId(), null));
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        ProxyCoordinator c = coordinator;
        if (c == null) return;
        Player player = event.getPlayer();
        String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
        offer(player, c.nextOffer(player.getUniqueId(), server));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        ProxyCoordinator c = coordinator;
        if (c != null) c.forget(event.getPlayer().getUniqueId());
    }

    /** Receives pack announcements from backends over the plugin channel. */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!channel.getId().equals(event.getIdentifier().getId())) return;
        // Never forward our channel: not to clients (backend announcements), and not to
        // backends (a client must not be able to pose as the proxy).
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) return;
        ProxyCoordinator c = coordinator;
        if (c == null) return;

        ProxyCoordinator.Result result = c.onBackendMessage(connection.getServerInfo().getName(), event.getData());
        if (!result.accepted()) return; // the coordinator logged why
        if (result.changed()) {
            logger.info("PackMerger proxy: {}", result.detail());
            if (c.settings().resendOnUpdate()) {
                for (Player player : proxy.getAllPlayers()) {
                    String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
                    offer(player, c.nextOffer(player.getUniqueId(), server));
                }
            }
        }
    }

    private void offer(Player player, PackInfo info) {
        if (info == null || !info.hasUrl()) return;
        ProxySettings settings = coordinator.settings();
        ResourcePackInfo.Builder builder = proxy.createResourcePackBuilder(info.url())
                .setId(ProxyCoordinator.PACK_ID)
                .setShouldForce(settings.required());
        byte[] hash = Hex.decodeOrNull(info.sha1Hex());
        if (hash != null && hash.length == 20) builder.setHash(hash); // a SHA-1 is 20 bytes
        if (settings.prompt() != null && !settings.prompt().isBlank()) {
            builder.setPrompt(Component.text(settings.prompt()));
        }
        player.sendResourcePackOffer(builder.build());
    }
}
