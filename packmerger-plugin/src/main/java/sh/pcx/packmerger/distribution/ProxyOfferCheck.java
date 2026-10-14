package sh.pcx.packmerger.distribution;

import sh.pcx.packmerger.common.PackMessage;
import sh.pcx.packmerger.common.PackMessaging;

import java.util.UUID;

/**
 * Decides whether a pack OFFER from a BungeeCord proxy is honoured. Bukkit-free so it
 * can be unit tested.
 *
 * <p>An OFFER arrives as a plugin message through the player's connection, which is
 * exactly what a modified client could send too. So an offer needs a configured
 * {@code distribution.proxy-secret} and a valid signature, and it may only target the
 * player whose connection carried it.</p>
 */
public final class ProxyOfferCheck {

    private ProxyOfferCheck() {}

    /** @return {@code null} if the offer may be sent to {@code carrier}, else why not */
    public static String rejectReason(PackMessaging.Decoded decoded, boolean secretConfigured, UUID carrier) {
        PackMessage msg = decoded.message();
        if (msg.kind() != PackMessage.Kind.OFFER) {
            return "unexpected " + msg.kind() + " message (only a proxy's pack offers are accepted here)";
        }
        if (!secretConfigured) {
            return "no distribution.proxy-secret is set on this server, so offers can't be authenticated";
        }
        if (decoded.auth() != PackMessaging.Auth.VALID) {
            return decoded.auth().rejectionReason();
        }
        if (msg.player() == null || !msg.player().equals(carrier)) {
            return "the offer is for another player (" + msg.player() + ")";
        }
        if (!msg.pack().hasUrl()) {
            return "the offer has no pack URL";
        }
        if (msg.packId() == null) {
            return "the offer has no pack id";
        }
        return null;
    }
}
