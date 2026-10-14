package sh.pcx.packmerger.distribution;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.common.PackInfo;
import sh.pcx.packmerger.common.PackMessage;
import sh.pcx.packmerger.common.PackMessaging;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProxyOfferCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID PACK_ID = UUID.randomUUID();
    private static final PackInfo PACK = new PackInfo("http://x/pack", "00".repeat(20));

    private static PackMessaging.Decoded decode(PackMessage msg, String signWith, String verifyWith) {
        return PackMessaging.decode(PackMessaging.encode(msg, signWith), verifyWith);
    }

    private static PackMessage offer(UUID player, PackInfo pack, UUID packId) {
        return PackMessage.offer("lobby", 1L, pack, player, packId, false, "");
    }

    @Test
    void validSignedOffer_forTheCarrier_isAccepted() {
        assertNull(ProxyOfferCheck.rejectReason(decode(offer(PLAYER, PACK, PACK_ID), "k", "k"), true, PLAYER));
    }

    @Test
    void withoutASecret_offersAreRefused() {
        String reason = ProxyOfferCheck.rejectReason(decode(offer(PLAYER, PACK, PACK_ID), null, null), false, PLAYER);
        assertTrue(reason.contains("no distribution.proxy-secret"), reason);
    }

    @Test
    void unsignedOrBadlySignedOffers_areRefused() {
        assertTrue(ProxyOfferCheck.rejectReason(decode(offer(PLAYER, PACK, PACK_ID), null, "k"), true, PLAYER)
                .contains("not signed"));
        assertTrue(ProxyOfferCheck.rejectReason(decode(offer(PLAYER, PACK, PACK_ID), "other", "k"), true, PLAYER)
                .contains("bad signature"));
    }

    @Test
    void offerForAnotherPlayer_isRefused() {
        String reason = ProxyOfferCheck.rejectReason(decode(offer(UUID.randomUUID(), PACK, PACK_ID), "k", "k"), true, PLAYER);
        assertTrue(reason.contains("another player"), reason);
    }

    @Test
    void publishMessages_andIncompleteOffers_areRefused() {
        assertTrue(ProxyOfferCheck.rejectReason(decode(PackMessage.publish("x", 1, PACK), "k", "k"), true, PLAYER)
                .contains("unexpected PUBLISH"));
        assertTrue(ProxyOfferCheck.rejectReason(decode(offer(PLAYER, new PackInfo(null, null), PACK_ID), "k", "k"), true, PLAYER)
                .contains("no pack URL"));
        assertTrue(ProxyOfferCheck.rejectReason(decode(offer(PLAYER, PACK, null), "k", "k"), true, PLAYER)
                .contains("no pack id"));
    }
}
