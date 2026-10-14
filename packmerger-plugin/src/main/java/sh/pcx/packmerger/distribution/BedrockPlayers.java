package sh.pcx.packmerger.distribution;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Detects Bedrock players connected through Geyser + Floodgate, so PackMerger does not
 * send them a Java resource pack they cannot use.
 *
 * <p>Floodgate is optional and not a compile dependency: its API is looked up
 * reflectively through Floodgate's own class loader the first time it is needed.
 * Without Floodgate installed, nobody is treated as a Bedrock player.</p>
 */
public final class BedrockPlayers {

    private final Object lock = new Object();
    private volatile boolean resolved;
    private Object floodgateApi;
    private Method isFloodgatePlayer;

    /** @return {@code true} if Floodgate is installed and reports the player as a Bedrock player */
    public boolean isBedrock(Player player) {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) return false;
        resolve(floodgate);
        if (isFloodgatePlayer != null) {
            try {
                return (Boolean) isFloodgatePlayer.invoke(floodgateApi, player.getUniqueId());
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall through to the UUID heuristic
            }
        }
        return looksLikeFloodgateUuid(player.getUniqueId());
    }

    private void resolve(Plugin floodgate) {
        if (resolved) return;
        synchronized (lock) {
            if (resolved) return;
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true,
                        floodgate.getClass().getClassLoader());
                floodgateApi = api.getMethod("getInstance").invoke(null);
                isFloodgatePlayer = api.getMethod("isFloodgatePlayer", UUID.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                floodgateApi = null;
                isFloodgatePlayer = null;
            }
            resolved = true;
        }
    }

    /**
     * Floodgate gives Bedrock players {@code new UUID(0, xuid)}; Java accounts never have
     * zero high bits. Only used as a fallback when Floodgate is installed but its API
     * could not be called.
     */
    static boolean looksLikeFloodgateUuid(UUID id) {
        return id != null && id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() != 0L;
    }
}
