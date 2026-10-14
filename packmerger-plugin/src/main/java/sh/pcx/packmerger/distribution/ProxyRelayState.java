package sh.pcx.packmerger.distribution;

import java.time.Instant;

/**
 * Bukkit-free bookkeeping for relaying the published pack to the proxy
 * ({@code distribution.proxy-notify}).
 *
 * <p>Plugin messages travel through a connected player, so with nobody online an update
 * can't be relayed. It then stays <em>pending</em> and goes out through the next player
 * whose connection listens on the channel. A pack is relayed once: re-publishing the
 * same URL and SHA-1 doesn't send it again, a newer pack replaces a pending one, and
 * {@link #reset()} (on {@code /pm reload}) forces the next publish to be relayed, e.g.
 * after proxy-notify was switched on or the secret changed.</p>
 */
public final class ProxyRelayState {

    /** A relay waiting for a carrier player. */
    public record Pending(String key, String url, byte[] payload) {}

    private Pending pending;
    private String lastRelayedKey;
    private Instant lastRelayedAt;
    private String lastRelayedVia;

    /**
     * Records a newly published pack.
     *
     * @return {@code true} if a relay must be attempted now: the pack differs from the
     *         last relayed one and isn't already waiting for a carrier (a re-publish of the
     *         waiting pack just refreshes its payload)
     */
    public synchronized boolean publish(String key, String url, byte[] payload) {
        if (key.equals(lastRelayedKey)) {
            pending = null;
            return false;
        }
        boolean alreadyWaiting = pending != null && pending.key().equals(key);
        pending = new Pending(key, url, payload);
        return !alreadyWaiting;
    }

    /** @return the relay waiting for a carrier, or {@code null} */
    public synchronized Pending pending() {
        return pending;
    }

    /**
     * Marks {@code relayed} as delivered through {@code via}, unless a newer pack has
     * replaced it in the meantime.
     *
     * @return {@code true} if it was still the pending relay
     */
    public synchronized boolean markRelayed(Pending relayed, String via, Instant at) {
        if (pending != relayed) return false;
        pending = null;
        lastRelayedKey = relayed.key();
        lastRelayedAt = at;
        lastRelayedVia = via;
        return true;
    }

    /** Forgets what was relayed, so the next publish is relayed again. */
    public synchronized void reset() {
        lastRelayedKey = null;
        pending = null;
    }

    public synchronized Instant lastRelayedAt() {
        return lastRelayedAt;
    }

    public synchronized String lastRelayedVia() {
        return lastRelayedVia;
    }
}
