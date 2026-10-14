package sh.pcx.packmerger.common.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.pcx.packmerger.common.PackInfo;
import sh.pcx.packmerger.common.PackMessage;
import sh.pcx.packmerger.common.PackMessaging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProxyCoordinatorTest {

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();
    private final ProxyCoordinator.Log log = new ProxyCoordinator.Log() {
        @Override public void info(String message) {}
        @Override public void warn(String message) { warnings.add(message); }
    };

    private static final PackInfo A1 = new PackInfo("https://a/1.zip", "aa01");
    private static final PackInfo A2 = new PackInfo("https://a/2.zip", "aa02");
    private static final PackInfo B1 = new PackInfo("https://b/1.zip", "bb01");

    private void writeConfig(String... lines) throws IOException {
        Files.writeString(dir.resolve(ProxySettings.FILE_NAME), String.join("\n", lines) + "\n");
    }

    private ProxyCoordinator start() {
        ProxySettings settings = ProxySettings.load(dir, warnings::add, Map.of("PM_SECRET", "k")::get);
        ProxyCoordinator c = new ProxyCoordinator(settings, dir, log);
        c.start();
        return c;
    }

    private static byte[] publish(PackInfo pack, long ts, String secret) {
        return PackMessaging.encode(PackMessage.publish("backend", ts, pack), secret);
    }

    @Test
    void pushedPack_survivesRestart_insteadOfStaleConfigUrl() throws IOException {
        writeConfig("url=https://config/old.zip", "sha1=0000", "secret=${PM_SECRET}");
        ProxyCoordinator first = start();
        assertEquals("https://config/old.zip", first.networkPack().url());
        assertEquals(ProxyCoordinator.CONFIG_SOURCE, first.networkSource());

        assertTrue(first.onBackendMessage("lobby", publish(A1, 10, "k")).changed());
        assertEquals(A1, first.networkPack());

        ProxyCoordinator restarted = start();
        assertEquals(A1, restarted.networkPack(), "the pushed pack is remembered across a restart");
        assertEquals("lobby", restarted.networkSource());
    }

    @Test
    void editedConfigUrl_takesOverOnRestart() throws IOException {
        writeConfig("url=https://config/old.zip", "secret=k");
        start().onBackendMessage("lobby", publish(A1, 10, "k"));
        writeConfig("url=https://config/new.zip", "secret=k");
        assertEquals("https://config/new.zip", start().networkPack().url());
    }

    @Test
    void secretConfigured_rejectsUnsignedWrongAndLegacy() throws IOException {
        writeConfig("secret=k");
        ProxyCoordinator c = start();
        assertFalse(c.onBackendMessage("s", publish(A1, 1, null)).accepted());
        assertFalse(c.onBackendMessage("s", publish(A1, 1, "wrong")).accepted());
        assertFalse(c.onBackendMessage("s", legacy(A1)).accepted());
        assertNull(c.networkPack());
        assertTrue(c.onBackendMessage("s", publish(A1, 1, "k")).accepted());
        assertEquals(A1, c.networkPack());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("bad signature")));
    }

    @Test
    void noSecret_acceptsUnsignedAndLegacy_butNotBadSignatures() throws IOException {
        writeConfig("url=");
        ProxyCoordinator c = start();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no 'secret' set")));
        assertTrue(c.onBackendMessage("s", publish(A1, 1, null)).accepted());
        assertTrue(c.onBackendMessage("s", legacy(A2)).accepted());
        assertEquals(A2, c.networkPack());
        // Signed messages are accepted (with a warning) because there is nothing to check against.
        assertTrue(c.onBackendMessage("s", publish(B1, 5, "k")).accepted());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no secret to check them")));
    }

    @Test
    void olderMessageFromTheSameServer_isRejected() throws IOException {
        writeConfig("secret=k");
        ProxyCoordinator c = start();
        assertTrue(c.onBackendMessage("s", publish(A2, 20, "k")).accepted());
        ProxyCoordinator.Result replay = c.onBackendMessage("s", publish(A1, 10, "k"));
        assertFalse(replay.accepted());
        assertEquals(A2, c.networkPack());
        // The same message again (retry on join) is fine and changes nothing.
        ProxyCoordinator.Result again = c.onBackendMessage("s", publish(A2, 20, "k"));
        assertTrue(again.accepted());
        assertFalse(again.changed());
        // Replay protection also holds after a restart.
        assertFalse(start().onBackendMessage("s", publish(A1, 10, "k")).accepted());
    }

    @Test
    void acceptFrom_limitsNetworkPublishers() throws IOException {
        writeConfig("secret=k", "accept-from=lobby, hub");
        ProxyCoordinator c = start();
        assertFalse(c.onBackendMessage("survival", publish(B1, 1, "k")).accepted());
        assertTrue(c.onBackendMessage("hub", publish(A1, 1, "k")).accepted());
        assertEquals(A1, c.networkPack());
    }

    @Test
    void competingPublishers_warn() throws IOException {
        writeConfig("secret=k");
        ProxyCoordinator c = start();
        c.onBackendMessage("lobby", publish(A1, 1, "k"));
        c.onBackendMessage("survival", publish(B1, 1, "k"));
        assertEquals(B1, c.networkPack());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'lobby' and 'survival' both publish a pack")));
    }

    @Test
    void offers_oncePerSession_andAgainAfterAnUpdate() throws IOException {
        writeConfig("secret=k");
        ProxyCoordinator c = start();
        UUID p = UUID.randomUUID();
        assertNull(c.nextOffer(p, null), "nothing to offer yet");
        c.onBackendMessage("lobby", publish(A1, 1, "k"));
        assertEquals(A1, c.nextOffer(p, null));
        assertNull(c.nextOffer(p, "lobby"), "already offered on login");
        assertNull(c.nextOffer(p, "survival"), "network mode: same pack on every server");
        assertTrue(c.onBackendMessage("lobby", publish(A2, 2, "k")).changed());
        assertEquals(A2, c.nextOffer(p, "survival"), "an update is offered again");
        c.forget(p);
        assertEquals(A2, c.nextOffer(p, null), "a new session gets it again");
    }

    @Test
    void perServerMode_offersEachBackendsPack_withConfigFallback() throws IOException {
        writeConfig("secret=k", "mode=per-server", "url=https://fallback/x.zip");
        ProxyCoordinator c = start();
        c.onBackendMessage("lobby", publish(A1, 1, "k"));
        c.onBackendMessage("survival", publish(B1, 1, "k"));
        assertTrue(warnings.stream().noneMatch(w -> w.contains("both publish")));
        UUID p = UUID.randomUUID();
        assertNull(c.nextOffer(p, null), "per-server waits for the first server");
        assertEquals(A1, c.nextOffer(p, "lobby"));
        assertEquals(B1, c.nextOffer(p, "survival"));
        assertEquals("https://fallback/x.zip", c.nextOffer(p, "creative").url());
        assertEquals(A1, c.nextOffer(p, "lobby"));
        assertNull(c.nextOffer(p, "lobby"));
        // per-server survives a restart too
        assertEquals(B1, start().packFor("survival"));
    }

    @Test
    void offerPayload_isSignedForTheBackend() throws IOException {
        writeConfig("secret=k", "required=true", "prompt=Hi");
        ProxyCoordinator c = start();
        UUID p = UUID.randomUUID();
        PackMessaging.Decoded d = PackMessaging.decode(c.offerPayload(p, "lobby", A1), "k");
        assertEquals(PackMessaging.Auth.VALID, d.auth());
        assertEquals(PackMessage.Kind.OFFER, d.message().kind());
        assertEquals(p, d.message().player());
        assertEquals(ProxyCoordinator.PACK_ID, d.message().packId());
        assertTrue(d.message().required());
        assertEquals("Hi", d.message().prompt());
        assertEquals(A1, d.message().pack());

        writeConfig("secret=");
        assertNull(start().offerPayload(p, "lobby", A1), "no unsigned offers");
    }

    @Test
    void unreadableState_startsEmpty() throws IOException {
        writeConfig("secret=k");
        Files.writeString(dir.resolve(ProxyState.FILE_NAME), "network.url=https://ok/x.zip\nnetwork.timestamp=notanumber\n");
        ProxyCoordinator c = start();
        assertEquals("https://ok/x.zip", c.networkPack().url());
    }

    @Test
    void malformedMessage_isRejected() throws IOException {
        writeConfig("secret=k");
        assertFalse(start().onBackendMessage("s", new byte[]{0, 0, 'P', 'M', 2}).accepted());
    }

    private static byte[] legacy(PackInfo info) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(b)) {
            out.writeUTF(info.url());
            out.writeUTF(info.sha1Hex());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return b.toByteArray();
    }
}
