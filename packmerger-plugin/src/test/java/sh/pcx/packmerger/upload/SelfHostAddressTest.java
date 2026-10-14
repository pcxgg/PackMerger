package sh.pcx.packmerger.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sh.pcx.packmerger.upload.SelfHostAddress.Candidate;
import sh.pcx.packmerger.upload.SelfHostAddress.Resolution;
import sh.pcx.packmerger.upload.SelfHostAddress.Source;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelfHostAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "localhost", "LOCALHOST", "foo.localhost", "0.0.0.0", "127.0.0.1", "127.1.2.3",
            "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.20", "169.254.10.10", "100.64.0.1",
            "100.127.255.255", "::1", "[::1]", "::", "fe80::1", "fe80::1%eth0", "fc00::1", "fd12:3456::1",
            "::ffff:192.168.1.1"})
    void nonRoutable(String host) {
        assertTrue(SelfHostAddress.isNonRoutable(host), host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3.4", "172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1", "8.8.8.8",
            "play.example.com", "2001:db8::1", "[2606:4700::1111]", "999.1.1.1"})
    void routable(String host) {
        assertFalse(SelfHostAddress.isNonRoutable(host), host);
    }

    @Test
    void publicUrlIsUsedAsCompleteUrl() {
        assertEquals("https://cdn.example.com/rp.zip", SelfHostAddress.normalizePublicUrl("https://cdn.example.com/rp.zip"));
        assertEquals("https://example.com/mc/pack", SelfHostAddress.normalizePublicUrl("https://example.com/mc/pack"));
        assertEquals("http://h:8080/pack", SelfHostAddress.normalizePublicUrl("http://h:8080/pack"));
        assertEquals("https://example.com/packs/", SelfHostAddress.normalizePublicUrl("https://example.com/packs/"));
    }

    @Test
    void publicUrlWithoutPathGetsPackAppended() {
        assertEquals("http://h:8080/pack", SelfHostAddress.normalizePublicUrl("http://h:8080"));
        assertEquals("http://h:8080/pack", SelfHostAddress.normalizePublicUrl("http://h:8080/"));
        assertEquals("http://h/pack?v=1", SelfHostAddress.normalizePublicUrl("http://h?v=1"));
        assertEquals("http://h/pack?v=1", SelfHostAddress.normalizePublicUrl("http://h/?v=1"));
    }

    @Test
    void publicUrlWinsOverEverything() {
        Resolution r = SelfHostAddress.resolve("https://cdn.example.com/rp.zip",
                List.of(new Candidate(Source.PUBLIC_HOST, "play.example.com")), 25566);
        assertEquals("https://cdn.example.com/rp.zip", r.url());
        assertEquals(Source.PUBLIC_URL, r.source());
        assertEquals("cdn.example.com", r.host());
        assertTrue(r.routable());
        assertNull(r.advice());
    }

    @Test
    void nonRoutablePublicUrlIsKeptButFlagged() {
        Resolution r = SelfHostAddress.resolve("http://192.168.1.5:8080", List.of(), 1);
        assertEquals("http://192.168.1.5:8080/pack", r.url());
        assertFalse(r.routable());
        assertTrue(r.advice().contains("upload.self-host.public-url"));
    }

    @Test
    void firstRoutableCandidateWins() {
        Resolution r = SelfHostAddress.resolve("", List.of(
                new Candidate(Source.PUBLIC_HOST, ""),
                new Candidate(Source.DETECTED_PUBLIC_IP, null),
                new Candidate(Source.SERVER_IP, "192.168.1.10"),
                new Candidate(Source.BIND_ADDRESS, "203.0.113.7")), 25566);
        assertEquals("http://203.0.113.7:25566/pack", r.url());
        assertEquals(Source.BIND_ADDRESS, r.source());
        assertTrue(r.routable());
    }

    @Test
    void publicHostBeatsDetectedIpAndServerIp() {
        Resolution r = SelfHostAddress.resolve(null, List.of(
                new Candidate(Source.PUBLIC_HOST, " play.example.com "),
                new Candidate(Source.DETECTED_PUBLIC_IP, "198.51.100.1"),
                new Candidate(Source.SERVER_IP, "198.51.100.2")), 8080);
        assertEquals("http://play.example.com:8080/pack", r.url());
        assertEquals(Source.PUBLIC_HOST, r.source());
    }

    @Test
    void detectedIpBeatsLanServerIp() {
        Resolution r = SelfHostAddress.resolve("", List.of(
                new Candidate(Source.PUBLIC_HOST, ""),
                new Candidate(Source.DETECTED_PUBLIC_IP, "198.51.100.1"),
                new Candidate(Source.SERVER_IP, "10.0.0.2")), 8080);
        assertEquals("http://198.51.100.1:8080/pack", r.url());
        assertEquals(Source.DETECTED_PUBLIC_IP, r.source());
    }

    @Test
    void lanServerIpIsUsedWhenNothingRoutableButFlagged() {
        Resolution r = SelfHostAddress.resolve("", List.of(
                new Candidate(Source.PUBLIC_HOST, ""),
                new Candidate(Source.SERVER_IP, "10.0.0.2"),
                new Candidate(Source.BIND_ADDRESS, "0.0.0.0")), 25566);
        assertEquals("http://10.0.0.2:25566/pack", r.url());
        assertEquals(Source.SERVER_IP, r.source());
        assertFalse(r.routable());
        String advice = r.advice();
        assertTrue(advice.contains("upload.self-host.public-url"), advice);
        assertTrue(advice.contains("upload.self-host.public-host"), advice);
        assertTrue(advice.contains("server-ip"), advice);
    }

    @Test
    void wildcardBindAddressIsAdvertisedAsLocalhost() {
        Resolution r = SelfHostAddress.resolve("", List.of(
                new Candidate(Source.SERVER_IP, ""),
                new Candidate(Source.BIND_ADDRESS, "0.0.0.0")), 28567);
        assertEquals("http://localhost:28567/pack", r.url());
        assertEquals("localhost", r.host());
        assertEquals(Source.BIND_ADDRESS, r.source());
        assertFalse(r.routable());
    }

    @Test
    void noCandidatesFallsBackToLocalhost() {
        Resolution r = SelfHostAddress.resolve("", List.of(), 1234);
        assertEquals("http://localhost:1234/pack", r.url());
        assertFalse(r.routable());
    }

    @Test
    void ipv6HostsAreBracketed() {
        assertEquals("http://[2001:db8::1]:25566/pack", SelfHostAddress.buildUrl("2001:db8::1", 25566));
        Resolution r = SelfHostAddress.resolve("http://[2001:db8::1]:8080/pack", List.of(), 1);
        assertEquals("2001:db8::1", r.host());
        assertTrue(r.routable());
    }

    @Test
    void pathOfPublicUrl() {
        assertEquals("/rp.zip", SelfHostAddress.pathOf("https://cdn.example.com/rp.zip"));
        assertEquals("/", SelfHostAddress.pathOf("https://cdn.example.com"));
    }
}
