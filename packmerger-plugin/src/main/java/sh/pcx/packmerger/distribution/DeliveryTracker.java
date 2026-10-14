package sh.pcx.packmerger.distribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session delivery bookkeeping, free of any Bukkit types so it can be unit tested.
 *
 * <p>Tracks two things:</p>
 * <ul>
 *   <li><b>Awaiting players</b>: players who joined while no pack was published yet.
 *       They are sent the pack as soon as the first one is published, whatever
 *       {@code on-new-pack.action} says.</li>
 *   <li><b>Self-heal resends</b>: when the client reports {@code FAILED_DOWNLOAD} or
 *       {@code DISCARDED} for PackMerger's pack, it is re-sent after a delay, at most
 *       {@code maxAttempts} times per session. {@code INVALID_URL} and
 *       {@code FAILED_RELOAD} are never retried because a resend cannot fix them.</li>
 * </ul>
 *
 * <p>State is in-memory only and is reset when a player joins or quits. The Minecraft
 * client drops server packs on disconnect, so nothing about delivery is worth keeping
 * across sessions.</p>
 *
 * <p>Thread-safe: all collections are concurrent, and each player's events arrive on
 * that player's region thread.</p>
 */
public final class DeliveryTracker {

    /** Mirror of Bukkit's {@code PlayerResourcePackStatusEvent.Status}, matched by name. */
    public enum Status {
        SUCCESSFULLY_LOADED, DECLINED, FAILED_DOWNLOAD, ACCEPTED, DOWNLOADED,
        INVALID_URL, FAILED_RELOAD, DISCARDED, OTHER;

        /** Maps a Bukkit status name; unknown names (future API values) become {@link #OTHER}. */
        public static Status of(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException | NullPointerException e) {
                return OTHER;
            }
        }
    }

    /** What the distributor should do in response to a status report. */
    public enum Action {
        /** In-progress status (accepted, downloaded) or one we don't act on. */
        NONE,
        /** The pack loaded; the player is settled. */
        LOADED,
        /** The player declined the pack. */
        DECLINED,
        /** Schedule a resend after the configured delay. */
        RESEND,
        /** A resend is already scheduled; ignore this duplicate report. */
        RESEND_ALREADY_PENDING,
        /** Retries are used up for this session. */
        GAVE_UP,
        /** The client could not use the URL; resending won't help. */
        INVALID_URL,
        /** The client downloaded the pack but failed to apply it; resending won't help. */
        FAILED_RELOAD
    }

    /**
     * @param action      what to do
     * @param attempt     for {@link Action#RESEND}, the 1-based number of this resend;
     *                    for {@link Action#GAVE_UP}, how many resends were made
     * @param maxAttempts the cap in force when the decision was made
     */
    public record Decision(Action action, int attempt, int maxAttempts) {}

    private final Set<UUID> awaiting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Set<UUID> resendPending = ConcurrentHashMap.newKeySet();
    private volatile int maxAttempts;

    /** @param maxAttempts resends allowed per session; 0 disables self-heal */
    public DeliveryTracker(int maxAttempts) {
        setMaxAttempts(maxAttempts);
    }

    /** Updates the per-session resend cap (e.g. after {@code /pm reload}). */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(0, maxAttempts);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** A new session starts: forget everything about the player's previous session. */
    public void onJoin(UUID player) {
        forget(player);
    }

    /** The player left: drop all state. */
    public void onQuit(UUID player) {
        forget(player);
    }

    private void forget(UUID player) {
        awaiting.remove(player);
        attempts.remove(player);
        resendPending.remove(player);
    }

    /** Remembers that the player joined before any pack was published. */
    public void markAwaiting(UUID player) {
        awaiting.add(player);
    }

    public boolean isAwaiting(UUID player) {
        return awaiting.contains(player);
    }

    /** Called when the player is actually sent a pack. */
    public void onSent(UUID player) {
        awaiting.remove(player);
    }

    /** @return the awaiting players (and clears the set), for the first publish to flush */
    public List<UUID> drainAwaiting() {
        List<UUID> drained = new ArrayList<>();
        for (UUID id : awaiting) {
            if (awaiting.remove(id)) drained.add(id);
        }
        return drained;
    }

    /** A scheduled resend fired (or was cancelled); allow the next failure to schedule another. */
    public void resendFinished(UUID player) {
        resendPending.remove(player);
    }

    /** @return resends made for the player this session */
    public int attempts(UUID player) {
        return attempts.getOrDefault(player, 0);
    }

    /**
     * Decides what to do with a status report for <em>PackMerger's</em> pack. Callers
     * must already have filtered out reports for other plugins' packs.
     */
    public Decision onStatus(UUID player, Status status) {
        int max = maxAttempts;
        return switch (status) {
            case SUCCESSFULLY_LOADED -> {
                attempts.remove(player);
                resendPending.remove(player);
                yield new Decision(Action.LOADED, 0, max);
            }
            case DECLINED -> {
                resendPending.remove(player);
                yield new Decision(Action.DECLINED, attempts(player), max);
            }
            case FAILED_DOWNLOAD, DISCARDED -> {
                if (!resendPending.add(player)) {
                    yield new Decision(Action.RESEND_ALREADY_PENDING, attempts(player), max);
                }
                int done = attempts(player);
                if (done >= max) {
                    resendPending.remove(player);
                    yield new Decision(Action.GAVE_UP, done, max);
                }
                attempts.put(player, done + 1);
                yield new Decision(Action.RESEND, done + 1, max);
            }
            case INVALID_URL -> {
                resendPending.remove(player);
                yield new Decision(Action.INVALID_URL, attempts(player), max);
            }
            case FAILED_RELOAD -> {
                resendPending.remove(player);
                yield new Decision(Action.FAILED_RELOAD, attempts(player), max);
            }
            default -> new Decision(Action.NONE, attempts(player), max);
        };
    }
}
