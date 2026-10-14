package sh.pcx.packmerger.distribution;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;
import sh.pcx.packmerger.common.PackInfo;
import sh.pcx.packmerger.common.PackMessage;
import sh.pcx.packmerger.common.PackMessaging;
import sh.pcx.packmerger.config.ConfigManager;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The backend side of the proxy link ({@link PackMessaging#CHANNEL}).
 *
 * <ul>
 *   <li><b>Relay</b> ({@code distribution.proxy-notify}): every newly published pack is sent
 *       to the PackMerger proxy plugin as a PUBLISH message, signed with
 *       {@code distribution.proxy-secret}. Plugin messages need a connected player; with
 *       nobody online the relay waits and goes out through the next player who joins
 *       (as soon as their connection registers the channel).</li>
 *   <li><b>Offers</b> (BungeeCord): Bungee can't send resource packs, so the Bungee plugin
 *       asks the backend to send the network pack with a signed OFFER message. Offers are
 *       honoured only with a secret configured and a valid signature, and only for the
 *       player whose connection carried them; they are sent whatever
 *       {@code distribution.enabled} says.</li>
 * </ul>
 */
public final class ProxyLink implements PluginMessageListener, Listener {

    /** Ticks to wait after a join before relaying a pending pack through that player. */
    private static final long JOIN_RELAY_DELAY_TICKS = 20L;

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;
    private final ProxyRelayState relay = new ProxyRelayState();
    private final AtomicBoolean warnedNoListener = new AtomicBoolean();
    private final AtomicBoolean warnedDoubleOffer = new AtomicBoolean();
    private final RejectionLimiter rejections = new RejectionLimiter();

    public ProxyLink(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
    }

    /** Registers the channel (both directions) and the join/register listeners. */
    public void register() {
        var messenger = Bukkit.getMessenger();
        messenger.registerOutgoingPluginChannel(plugin.getLoader(), PackMessaging.CHANNEL);
        messenger.registerIncomingPluginChannel(plugin.getLoader(), PackMessaging.CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin.getLoader());
        checkConfiguration();
    }

    /** Re-reads settings after {@code /pm reload}: the next publish is relayed again. */
    public void reloadSettings() {
        relay.reset();
        warnedNoListener.set(false);
        warnedDoubleOffer.set(false);
        rejections.clear();
        checkConfiguration();
    }

    /** Warns about configurations that send the pack twice or leave the link unauthenticated. */
    private void checkConfiguration() {
        ConfigManager config = plugin.getConfigManager();
        if (!config.isProxyNotify()) return;
        if (config.isDistributionEnabled()) {
            logger.warning("[proxy] distribution.proxy-notify is on while distribution.enabled is true: if the "
                    + "PackMerger proxy plugin offers this pack, players get it twice (once from this server and "
                    + "once from the proxy). When the proxy distributes the pack, set distribution.enabled: false "
                    + "on the backends.");
        }
        if (config.getProxySecret() == null) {
            logger.warning("[proxy] distribution.proxy-notify is on but distribution.proxy-secret is empty: pack "
                    + "messages to the proxy are not signed, so the proxy can't tell them from forged ones. Set the "
                    + "same secret here and in the proxy plugin's config.properties.");
        }
    }

    /** Called after every successful publish. Relays the pack if it is new to the proxy. */
    public void onPublished(PublishedPack pack) {
        ConfigManager config = plugin.getConfigManager();
        if (!config.isProxyNotify()) return;
        PackInfo info = new PackInfo(pack.url(), pack.sha1Hex());
        byte[] payload = PackMessaging.encode(PackMessage.publish(config.getServerName(),
                pack.publishedAt().toEpochMilli(), info), config.getProxySecret());
        if (!relay.publish(pack.url() + "|" + pack.sha1Hex(), pack.url(), payload)) return;

        // Pick a carrier on the global region; the send itself runs on the player's region.
        Bukkit.getGlobalRegionScheduler().run(plugin.getLoader(), task -> {
            var players = Bukkit.getOnlinePlayers();
            if (players.isEmpty()) {
                logger.info("[proxy] no players online; the pack update will be relayed to the proxy "
                        + "through the next player who joins");
                return;
            }
            for (Player p : players) {
                if (p.getListeningPluginChannels().contains(PackMessaging.CHANNEL)) {
                    relayThrough(p, "published");
                    return;
                }
            }
            warnNoListener(players.iterator().next());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (relay.pending() == null || !plugin.getConfigManager().isProxyNotify()) return;
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin.getLoader(), task -> {
            if (relay.pending() == null || !player.isOnline()) return;
            if (player.getListeningPluginChannels().contains(PackMessaging.CHANNEL)) {
                relayThrough(player, "joined");
            } else {
                warnNoListener(player);
            }
        }, null, JOIN_RELAY_DELAY_TICKS);
    }

    /** The proxy registers the channel on the player's connection; relay right away. */
    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (!PackMessaging.CHANNEL.equals(event.getChannel())) return;
        if (relay.pending() == null || !plugin.getConfigManager().isProxyNotify()) return;
        Player player = event.getPlayer();
        player.getScheduler().run(plugin.getLoader(), task -> relayThrough(player, "registered the channel"), null);
    }

    /** Runs on the player's region thread (or schedules itself there). */
    private void relayThrough(Player player, String trigger) {
        ProxyRelayState.Pending pending = relay.pending();
        if (pending == null || !player.isOnline()) return;
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            player.getScheduler().run(plugin.getLoader(), task -> relayThrough(player, trigger), null);
            return;
        }
        player.sendPluginMessage(plugin.getLoader(), PackMessaging.CHANNEL, pending.payload());
        if (relay.markRelayed(pending, player.getName(), Instant.now())) {
            logger.info("[proxy] relayed the pack to the proxy through " + player.getName() + " (" + trigger + "; "
                    + (plugin.getConfigManager().getProxySecret() != null ? "signed" : "unsigned") + "): "
                    + pending.url());
        }
    }

    private void warnNoListener(Player player) {
        if (warnedNoListener.compareAndSet(false, true)) {
            logger.warning("[proxy] distribution.proxy-notify is on, but " + player.getName() + "'s connection "
                    + "does not listen on " + PackMessaging.CHANNEL + ": is the PackMerger proxy plugin "
                    + "(packmerger-velocity or packmerger-bungee) installed on the proxy? The update stays "
                    + "pending until a player whose connection listens joins.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        rejections.forget(event.getPlayer().getUniqueId());
    }

    /** Warns on a player's first rejected message of the session; later ones are debug only. */
    private void logRejection(Player player, String text) {
        if (rejections.shouldWarn(player.getUniqueId())) {
            logger.warning("[proxy] " + text + " (further rejections from this player are logged at debug level)");
        } else {
            logger.debug("[proxy] " + text);
        }
    }

    /** OFFER messages from a BungeeCord proxy (anything else on the channel is ignored). */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!PackMessaging.CHANNEL.equals(channel)) return;
        ConfigManager config = plugin.getConfigManager();
        PackMessaging.Decoded decoded;
        try {
            decoded = PackMessaging.decode(message, config.getProxySecret());
        } catch (IllegalArgumentException e) {
            logRejection(player, "ignored a malformed pack message via " + player.getName() + " (" + e.getMessage() + ")");
            return;
        }
        String reason = ProxyOfferCheck.rejectReason(decoded, config.getProxySecret() != null, player.getUniqueId());
        if (reason != null) {
            logRejection(player, "rejected a pack offer via " + player.getName() + ": " + reason);
            return;
        }
        PackMessage offer = decoded.message();
        if (config.isDistributionEnabled() && warnedDoubleOffer.compareAndSet(false, true)) {
            logger.warning("[proxy] the proxy asks this server to send the network pack, and this server also "
                    + "sends its own (distribution.enabled: true), so players get both. Set distribution.enabled: "
                    + "false on the backends when the proxy distributes the pack.");
        }
        logger.info("[proxy] sending the proxy's pack to " + player.getName() + ": " + offer.pack().url());
        plugin.getDistributor().sendProxyOffer(player, offer);
    }

    /** @return the "Proxy link" line of {@code /pm status} */
    public String describe() {
        ConfigManager config = plugin.getConfigManager();
        String auth = config.getProxySecret() != null ? "signed" : "UNSIGNED (no distribution.proxy-secret)";
        if (!config.isProxyNotify()) {
            return "notify off; proxy offers " + (config.getProxySecret() != null ? "accepted (signed only)"
                    : "refused (no distribution.proxy-secret)");
        }
        ProxyRelayState.Pending pending = relay.pending();
        Instant at = relay.lastRelayedAt();
        String state;
        if (pending != null) {
            state = "update pending until a player joins";
        } else if (at != null) {
            state = "last relayed at " + java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .format(java.time.LocalTime.ofInstant(at, java.time.ZoneId.systemDefault()))
                    + " through " + relay.lastRelayedVia();
        } else {
            state = "nothing relayed yet";
        }
        return "notify on, " + auth + "; " + state;
    }
}
