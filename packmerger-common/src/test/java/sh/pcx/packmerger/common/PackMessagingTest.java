package sh.pcx.packmerger.common;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PackMessagingTest {

    private static final PackInfo PACK = new PackInfo("https://cdn.example.com/abc.zip", "deadbeef");

    @Test
    void publish_roundTrip_signed() {
        PackMessage in = PackMessage.publish("lobby", 1234L, PACK);
        PackMessaging.Decoded out = PackMessaging.decode(PackMessaging.encode(in, "s3cret"), "s3cret");
        assertEquals(PackMessaging.Auth.VALID, out.auth());
        assertEquals(in, out.message());
        assertTrue(out.message().pack().hasUrl());
        assertTrue(out.message().pack().hasHash());
    }

    @Test
    void offer_roundTrip_keepsPlayerPackIdAndFlags() {
        UUID player = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        PackMessage in = PackMessage.offer("survival", 99L, PACK, player, packId, true, "Please accept");
        PackMessage out = PackMessaging.decode(PackMessaging.encode(in, "k"), "k").message();
        assertEquals(PackMessage.Kind.OFFER, out.kind());
        assertEquals(player, out.player());
        assertEquals(packId, out.packId());
        assertTrue(out.required());
        assertEquals("Please accept", out.prompt());
    }

    @Test
    void nullHash_becomesNull() {
        PackMessage out = PackMessaging.decode(
                PackMessaging.encode(PackMessage.publish("a", 1, new PackInfo("https://x/y.zip", null)), null), null).message();
        assertEquals("https://x/y.zip", out.pack().url());
        assertNull(out.pack().sha1Hex());
        assertFalse(out.pack().hasHash());
        assertNull(out.player());
        assertNull(out.packId());
    }

    @Test
    void wrongSecret_isInvalid() {
        byte[] bytes = PackMessaging.encode(PackMessage.publish("a", 1, PACK), "right");
        assertEquals(PackMessaging.Auth.INVALID, PackMessaging.decode(bytes, "wrong").auth());
    }

    @Test
    void tamperedBody_isInvalid() {
        byte[] bytes = PackMessaging.encode(PackMessage.publish("a", 1, PACK), "k");
        // Flip a byte inside the URL ("https://cdn..." -> "https://cdo...").
        String asText = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int i = asText.indexOf("cdn");
        bytes[i + 2] = 'o';
        PackMessaging.Decoded d = PackMessaging.decode(bytes, "k");
        assertEquals(PackMessaging.Auth.INVALID, d.auth());
        assertEquals("https://cdo.example.com/abc.zip", d.message().pack().url());
    }

    @Test
    void unsignedAndUncheckedAndLegacy() {
        byte[] unsigned = PackMessaging.encode(PackMessage.publish("a", 1, PACK), "");
        assertEquals(PackMessaging.Auth.UNSIGNED, PackMessaging.decode(unsigned, "k").auth());
        assertEquals(PackMessaging.Auth.UNSIGNED, PackMessaging.decode(unsigned, null).auth());

        byte[] signed = PackMessaging.encode(PackMessage.publish("a", 1, PACK), "k");
        assertEquals(PackMessaging.Auth.NOT_CHECKED, PackMessaging.decode(signed, null).auth());

        PackMessaging.Decoded legacy = PackMessaging.decode(PackMessaging.encodeLegacy(PACK), "k");
        assertEquals(PackMessaging.Auth.LEGACY, legacy.auth());
        assertEquals(PackMessage.Kind.PUBLISH, legacy.message().kind());
        assertEquals(PACK, legacy.message().pack());
    }

    @Test
    void acceptanceRule() {
        for (PackMessaging.Auth a : PackMessaging.Auth.values()) {
            assertEquals(a == PackMessaging.Auth.VALID, a.acceptable(true), a + " with a secret");
            assertEquals(a != PackMessaging.Auth.INVALID, a.acceptable(false), a + " without a secret");
        }
    }

    @Test
    void malformed_throws() {
        byte[] good = PackMessaging.encode(PackMessage.publish("a", 1, PACK), "k");
        assertThrows(IllegalArgumentException.class,
                () -> PackMessaging.decode(java.util.Arrays.copyOf(good, good.length - 3), "k"));
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(IllegalArgumentException.class, () -> PackMessaging.decode(trailing, "k"));
        byte[] badVersion = good.clone();
        badVersion[4] = 9;
        assertThrows(IllegalArgumentException.class, () -> PackMessaging.decode(badVersion, "k"));
        assertThrows(IllegalArgumentException.class, () -> PackMessaging.decode(new byte[]{1}, null));
    }

    @Test
    void legacyDecoderSeesNoUrlInTheNewFormat() {
        // A 1.2 proxy reads the first UTF string as the URL: the magic makes it empty, so
        // the old proxy ignores the message instead of offering garbage.
        byte[] bytes = PackMessaging.encode(PackMessage.publish("a", 1, PACK), "k");
        assertEquals(0, bytes[0]);
        assertEquals(0, bytes[1]);
    }

    @Test
    void signatureIsHmacSha256OverTheBody() throws Exception {
        byte[] bytes = PackMessaging.encode(PackMessage.publish("a", 1, PACK), "k");
        int sigLen = 32;
        int bodyLen = bytes.length - sigLen - 2;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("k".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(java.util.Arrays.copyOf(bytes, bodyLen));
        assertArrayEquals(expected, java.util.Arrays.copyOfRange(bytes, bodyLen + 2, bytes.length));
    }

    @Test
    void secrets_resolveEnvironmentVariables() {
        Map<String, String> env = Map.of("PM_SECRET", "abc", "BLANK", " ");
        assertEquals("abc", Secrets.resolve("${PM_SECRET}", env::get));
        assertEquals("pre-abc-post", Secrets.resolve("pre-${PM_SECRET}-post", env::get));
        assertNull(Secrets.resolve("${UNSET}", env::get));
        assertNull(Secrets.resolve("  ", env::get));
        assertNull(Secrets.resolve(null, env::get));
        assertEquals("literal", Secrets.resolve(" literal ", env::get));
        assertEquals(java.util.List.of("UNSET", "BLANK"), Secrets.missingVariables("${UNSET}${PM_SECRET}${BLANK}", env::get));
    }

    @Test
    void hex() {
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad}, Hex.decodeOrNull("dead"));
        assertNull(Hex.decodeOrNull("abc"));
        assertNull(Hex.decodeOrNull("zz"));
        assertNull(Hex.decodeOrNull(null));
        assertEquals("dead", Hex.encode(new byte[]{(byte) 0xde, (byte) 0xad}));
    }
}
