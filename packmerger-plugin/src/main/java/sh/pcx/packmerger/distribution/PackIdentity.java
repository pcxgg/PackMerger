package sh.pcx.packmerger.distribution;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stable resource-pack UUID for this server.
 *
 * <p>The client keys server packs by UUID. PackMerger used to derive the UUID from the
 * pack's SHA-1, so every new pack had a new id: in add mode an update stacked a second
 * PackMerger pack next to the stale one, and replace mode had to clear <em>every</em>
 * pack (including the proxy's or another plugin's) to get rid of ours.</p>
 *
 * <p>With one UUID per server, re-sending replaces PackMerger's own pack in place and
 * {@code removeResourcePacks(id)} touches nothing else. The id is derived from the
 * server name so each backend in a network has its own, and it stays the same across
 * merges and restarts.</p>
 */
public final class PackIdentity {

    private PackIdentity() {}

    /** @return {@code UUID.nameUUIDFromBytes("packmerger:" + serverName)} */
    public static UUID forServer(String serverName) {
        String name = serverName == null ? "" : serverName;
        return UUID.nameUUIDFromBytes(("packmerger:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
