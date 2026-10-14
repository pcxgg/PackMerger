package sh.pcx.packmerger.common;

import java.util.UUID;

/**
 * One message on the {@link PackMessaging#CHANNEL} channel.
 *
 * <ul>
 *   <li>{@link Kind#PUBLISH}: backend to proxy. "This server now serves {@code pack}."
 *       {@code player} and {@code packId} are unused ({@code null}).</li>
 *   <li>{@link Kind#OFFER}: proxy to backend (BungeeCord, which has no resource-pack
 *       API). "Send {@code pack} to {@code player} under {@code packId}." The backend
 *       sends it on the proxy's behalf.</li>
 * </ul>
 *
 * @param kind      what the message asks for
 * @param server    the sender's server name (backend {@code server-name} for PUBLISH,
 *                  the target backend's proxy name for OFFER); informational
 * @param timestamp epoch millis when the pack was published (PUBLISH) or the offer made
 *                  (OFFER); the proxy rejects a PUBLISH older than the last one it
 *                  accepted from the same server, so an old message can't be replayed
 * @param pack      the pack's URL and SHA-1
 * @param player    OFFER only: the player to send the pack to
 * @param packId    OFFER only: the resource-pack UUID to send it under
 * @param required  OFFER only: the client "required" flag
 * @param prompt    OFFER only: the prompt text, or empty
 */
public record PackMessage(Kind kind, String server, long timestamp, PackInfo pack,
                          UUID player, UUID packId, boolean required, String prompt) {

    /** Message kinds; the byte value is part of the wire format. */
    public enum Kind {
        PUBLISH(1), OFFER(2);

        final int wire;

        Kind(int wire) { this.wire = wire; }

        static Kind fromWire(int value) {
            for (Kind k : values()) if (k.wire == value) return k;
            throw new IllegalArgumentException("unknown message kind " + value);
        }
    }

    public PackMessage {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (pack == null) pack = new PackInfo(null, null);
        server = server == null ? "" : server;
        prompt = prompt == null ? "" : prompt;
    }

    /** A backend's "this is my current pack" announcement. */
    public static PackMessage publish(String server, long timestamp, PackInfo pack) {
        return new PackMessage(Kind.PUBLISH, server, timestamp, pack, null, null, false, "");
    }

    /** A proxy's "send this pack to that player" request. */
    public static PackMessage offer(String server, long timestamp, PackInfo pack, UUID player,
                                    UUID packId, boolean required, String prompt) {
        return new PackMessage(Kind.OFFER, server, timestamp, pack, player, packId, required, prompt);
    }
}
