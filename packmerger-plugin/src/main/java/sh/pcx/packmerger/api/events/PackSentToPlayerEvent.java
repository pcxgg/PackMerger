package sh.pcx.packmerger.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the player's region thread after PackMerger dispatches the resource pack
 * to a player via {@link sh.pcx.packmerger.distribution.PackDistributor#sendPack}.
 *
 * <p>This does not indicate the player accepted or successfully loaded the
 * pack — only that the send was issued. For load / decline / failure hooks,
 * listen to Bukkit's own {@code PlayerResourcePackStatusEvent}.</p>
 *
 * <p>The event is not fired when the send is skipped (distribution disabled,
 * Bedrock player, or no pack published yet) — only actual dispatches generate
 * this event.</p>
 */
public class PackSentToPlayerEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String url;
    private final String sha1Hex;
    private final boolean bypassedCache;

    public PackSentToPlayerEvent(Player player, String url, String sha1Hex, boolean bypassedCache) {
        super(false); // fired synchronously on the player's region thread
        this.player = player;
        this.url = url;
        this.sha1Hex = sha1Hex;
        this.bypassedCache = bypassedCache;
    }

    public Player getPlayer() { return player; }

    /** @return the pack URL sent to the player */
    public String getUrl() { return url; }

    /** @return the hex-encoded SHA-1 of the pack sent */
    public String getSha1Hex() { return sha1Hex; }

    /**
     * @return {@code true} for sends PackMerger triggered outside the normal join send:
     *         {@code /pm apply}, an {@code on-new-pack: resend}, a self-heal resend after a
     *         failed download, or the first publish reaching players who joined before a
     *         pack existed. (The name predates 1.3.0, when the player cache was removed.)
     */
    public boolean bypassedCache() { return bypassedCache; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
