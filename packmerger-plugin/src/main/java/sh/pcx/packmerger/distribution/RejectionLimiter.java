package sh.pcx.packmerger.distribution;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets a rejection be logged loudly once per player per session; later ones from the same
 * player are logged quietly, so a modified client can't flood the log. Bukkit-free.
 */
final class RejectionLimiter {

    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();

    /** @return {@code true} the first time it is called for {@code player} since {@link #forget} */
    boolean shouldWarn(UUID player) {
        return warned.add(player);
    }

    /** The player's session ended. */
    void forget(UUID player) {
        warned.remove(player);
    }

    void clear() {
        warned.clear();
    }
}
