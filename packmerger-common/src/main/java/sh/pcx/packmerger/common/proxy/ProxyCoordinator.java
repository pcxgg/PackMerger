package sh.pcx.packmerger.common.proxy;

import sh.pcx.packmerger.common.PackInfo;
import sh.pcx.packmerger.common.PackMessage;
import sh.pcx.packmerger.common.PackMessaging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Platform-independent core of the proxy plugins (Velocity and BungeeCord).
 *
 * <p>It decides which backend messages to accept (signature, replay, {@code accept-from}),
 * remembers the accepted packs in {@link ProxyState} (persisted), and decides which pack a
 * player should be offered and when, so each player is offered a given pack once per
 * session and gets updates in place under the single {@link #PACK_ID}.</p>
 *
 * <p>The platform adapters only translate events: login, server connect, quit, plugin
 * messages, and "offer this pack to that player".</p>
 */
public final class ProxyCoordinator {

    /** The resource-pack UUID the proxy offers its pack under (stable, so updates replace it). */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("packmerger:proxy".getBytes(StandardCharsets.UTF_8));

    /** Source name recorded for the pack from config.properties. */
    public static final String CONFIG_SOURCE = "config.properties";

    /** Logging sink, so this class stays free of slf4j/java.util.logging choices. */
    public interface Log {
        void info(String message);

        void warn(String message);
    }

    /**
     * Outcome of a backend message.
     *
     * @param accepted whether the message was stored
     * @param changed  whether a pack someone is offered changed (re-offer to online players)
     * @param detail   what happened, for the log
     */
    public record Result(boolean accepted, boolean changed, String detail) {}

    private final ProxySettings settings;
    private final Path stateFile;
    private final Log log;
    private ProxyState state = new ProxyState();

    /** Player -> {@code url|sha1} of the pack last offered in this session. */
    private final Map<UUID, String> offered = new ConcurrentHashMap<>();
    /** Pairs of servers already warned about publishing competing network packs. */
    private final Set<String> conflictWarned = new HashSet<>();
    private final Set<String> rejectWarned = new HashSet<>();

    public ProxyCoordinator(ProxySettings settings, Path dataDir, Log log) {
        this.settings = settings;
        this.stateFile = dataDir.resolve(ProxyState.FILE_NAME);
        this.log = log;
    }

    public ProxySettings settings() {
        return settings;
    }

    /**
     * Loads the persisted state and applies config.properties: an edited {@code url}/{@code sha1}
     * takes over, an unchanged one does not override a pack a backend pushed later.
     */
    public synchronized void start() {
        try {
            state = ProxyState.load(stateFile);
        } catch (IOException | RuntimeException e) {
            log.warn("could not read " + stateFile + " (" + e.getMessage() + "); starting without a remembered pack");
            state = new ProxyState();
        }
        PackInfo configured = settings.staticPack();
        String fingerprint = key(configured);
        boolean configEdited = !Objects.equals(fingerprint, state.configFingerprint);
        if (configured.hasUrl() && (configEdited || state.network == null)) {
            state.network = new ProxyState.Entry(configured, 0L, CONFIG_SOURCE);
        }
        state.configFingerprint = fingerprint;
        save();

        if (!settings.hasSecret()) {
            log.warn("no 'secret' set in " + ProxySettings.FILE_NAME + ": pack messages are not "
                    + "authenticated, so any backend (or anyone who can run a backend behind this proxy) "
                    + "can replace the pack. Set the same secret here and as distribution.proxy-secret "
                    + "on the backends.");
        }
    }

    /** @return the network pack (NETWORK mode, or the fallback in PER_SERVER mode), or {@code null} */
    public synchronized PackInfo networkPack() {
        if (settings.mode() == ProxySettings.Mode.PER_SERVER) {
            return settings.staticPack().hasUrl() ? settings.staticPack() : null;
        }
        return state.network == null ? null : state.network.pack();
    }

    /** @return where the network pack came from, for the startup log */
    public synchronized String networkSource() {
        return state.network == null ? null : state.network.source();
    }

    /** @return the pack a player on {@code server} should have, or {@code null} for none */
    public synchronized PackInfo packFor(String server) {
        if (settings.mode() == ProxySettings.Mode.NETWORK) return networkPack();
        if (server == null) return null;
        ProxyState.Entry e = state.servers.get(server);
        return e != null ? e.pack() : networkPack();
    }

    /**
     * Handles a {@link PackMessaging#CHANNEL} message a backend sent.
     *
     * @param server the proxy's name for the backend it came through (trusted, unlike the
     *               name inside the message)
     */
    public synchronized Result onBackendMessage(String server, byte[] data) {
        PackMessaging.Decoded decoded;
        try {
            decoded = PackMessaging.decode(data, settings.secret());
        } catch (IllegalArgumentException e) {
            return reject(server, "malformed message (" + e.getMessage() + ")");
        }
        PackMessage msg = decoded.message();
        if (msg.kind() != PackMessage.Kind.PUBLISH) {
            return reject(server, "unexpected " + msg.kind() + " message");
        }
        if (!decoded.auth().acceptable(settings.hasSecret())) {
            return reject(server, decoded.auth().rejectionReason());
        }
        if (decoded.auth() == PackMessaging.Auth.NOT_CHECKED) {
            warnOnce("unchecked:" + server, "pack messages from " + server + " are signed, but this proxy has "
                    + "no secret to check them; set secret in " + ProxySettings.FILE_NAME);
        }
        if (!msg.pack().hasUrl()) {
            return reject(server, "the message has no pack URL");
        }
        if (settings.mode() == ProxySettings.Mode.NETWORK && !settings.acceptFrom().isEmpty()
                && !settings.acceptFrom().contains(server)) {
            return reject(server, "'" + server + "' is not listed in accept-from");
        }
        ProxyState.Entry previousForServer = state.servers.get(server);
        boolean legacy = decoded.auth() == PackMessaging.Auth.LEGACY;
        if (!legacy && previousForServer != null && msg.timestamp() < previousForServer.timestamp()) {
            return reject(server, "older than the pack it already published (replayed or out-of-order message)");
        }

        ProxyState.Entry entry = new ProxyState.Entry(msg.pack(), msg.timestamp(), server);
        boolean changed;
        if (settings.mode() == ProxySettings.Mode.NETWORK) {
            ProxyState.Entry previous = state.network;
            changed = previous == null || !key(previous.pack()).equals(key(msg.pack()));
            if (changed && previous != null && settings.acceptFrom().isEmpty()
                    && !previous.source().equals(server) && !CONFIG_SOURCE.equals(previous.source())) {
                String pair = previous.source() + "|" + server;
                if (conflictWarned.add(pair)) {
                    log.warn("backends '" + previous.source() + "' and '" + server + "' both publish a pack, but "
                            + "network mode offers only one (the latest). Set accept-from=<server> in "
                            + ProxySettings.FILE_NAME + ", or mode=per-server.");
                }
            }
            state.network = entry;
        } else {
            changed = previousForServer == null || !key(previousForServer.pack()).equals(key(msg.pack()));
        }
        state.servers.put(server, entry);
        save();
        String detail = (changed ? "pack updated from " : "pack unchanged from ") + server + " -> " + msg.pack().url();
        return new Result(true, changed, detail);
    }

    /**
     * The pack to offer {@code player} now, or {@code null} if they already have it (or
     * there is none). Marks it as offered, so call it only when you will send it.
     *
     * @param server the backend the player is on, or {@code null} right after login
     */
    public PackInfo nextOffer(UUID player, String server) {
        PackInfo pack = packFor(server);
        if (pack == null || !pack.hasUrl()) return null;
        String key = key(pack);
        String previous = offered.put(player, key);
        return key.equals(previous) ? null : pack;
    }

    /** Forgets a player's session (the client drops server packs on disconnect). */
    public void forget(UUID player) {
        offered.remove(player);
    }

    /**
     * Builds a signed OFFER for a backend to send on the proxy's behalf (BungeeCord), or
     * {@code null} if no secret is set (backends refuse unsigned offers).
     */
    public byte[] offerPayload(UUID player, String server, PackInfo pack) {
        if (!settings.hasSecret()) return null;
        return PackMessaging.encode(PackMessage.offer(server, System.currentTimeMillis(), pack, player,
                PACK_ID, settings.required(), settings.prompt()), settings.secret());
    }

    private Result reject(String server, String reason) {
        warnOnce("reject:" + server + ":" + reason, "ignored a pack message from " + server + ": " + reason);
        return new Result(false, false, reason);
    }

    private void warnOnce(String key, String message) {
        if (rejectWarned.add(key)) log.warn(message);
    }

    private void save() {
        try {
            state.save(stateFile);
        } catch (IOException e) {
            log.warn("could not save " + stateFile + " (" + e.getMessage() + "); the pack is not remembered "
                    + "across a proxy restart");
        }
    }

    /** {@code url|sha1}, the identity of a pack as the client sees it. */
    static String key(PackInfo pack) {
        return (pack.url() == null ? "" : pack.url()) + "|" + (pack.sha1Hex() == null ? "" : pack.sha1Hex());
    }
}
