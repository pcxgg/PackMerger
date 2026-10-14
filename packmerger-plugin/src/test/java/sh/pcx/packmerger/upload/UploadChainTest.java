package sh.pcx.packmerger.upload;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UploadChainTest {

    private static final File FILE = new File("pack.zip");

    private static UploadChain.Step ok(String url) {
        return (f, h) -> url;
    }

    private static UploadChain.Step failing(String message) {
        return (f, h) -> { throw new IOException(message); };
    }

    private static UploadChain.Step checkFails(String url, String reason) {
        return new UploadChain.Step() {
            @Override public String upload(File file, String sha1Hex) { return url; }
            @Override public String check(String u) { return reason; }
        };
    }

    @Test
    void orderPutsPrimaryFirstAndDeduplicates() {
        assertEquals(List.of("self-host", "s3"), UploadChain.order("self-host", List.of("S3", "self-host", " s3 ", "")));
    }

    @Test
    void primaryThatWorksIsUsed() throws Exception {
        List<String> tried = new ArrayList<>();
        UploadChain.Outcome o = UploadChain.run(List.of("self-host", "s3"),
                n -> { tried.add(n); return ok("http://" + n); }, FILE, "abc");
        assertEquals("http://self-host", o.url());
        assertEquals("self-host", o.provider());
        assertFalse(o.fallback());
        assertFalse(o.degraded());
        assertEquals(List.of("self-host"), tried, "fallbacks are not touched when the primary works");
        assertEquals("self-host", o.describe());
    }

    @Test
    void failedCheckFallsBackToNextProvider() throws Exception {
        Map<String, UploadChain.Step> steps = Map.of(
                "self-host", checkFails("http://localhost/pack", "advertised URL check failed: timeout"),
                "s3", ok("https://r2.example.com/abc.zip"));
        UploadChain.Outcome o = UploadChain.run(List.of("self-host", "s3"), steps::get, FILE, "abc");
        assertEquals("https://r2.example.com/abc.zip", o.url());
        assertTrue(o.fallback());
        assertFalse(o.degraded());
        assertTrue(o.describe().startsWith("s3 (fallback): self-host: advertised URL check failed"), o.describe());
    }

    @Test
    void uploadErrorFallsBack() throws Exception {
        Map<String, UploadChain.Step> steps = Map.of("polymath", failing("connection refused"), "self-host", ok("http://h/pack"));
        UploadChain.Outcome o = UploadChain.run(List.of("polymath", "self-host"), steps::get, FILE, "abc");
        assertEquals("http://h/pack", o.url());
        assertTrue(o.fallback());
        assertEquals(List.of("polymath upload failed: connection refused"), o.notes());
    }

    @Test
    void unconfiguredFallbackIsSkipped() throws Exception {
        Map<String, UploadChain.Step> steps = Map.of("self-host", checkFails("http://localhost/pack", "unreachable"));
        UploadChain.Outcome o = UploadChain.run(List.of("self-host", "s3"), steps::get, FILE, "abc");
        // Nothing better: the self-host URL is still published, flagged as degraded.
        assertEquals("http://localhost/pack", o.url());
        assertEquals("self-host", o.provider());
        assertTrue(o.degraded());
        assertFalse(o.fallback());
        assertEquals(List.of("self-host: unreachable", "s3 is not configured"), o.notes());
        assertEquals("self-host (not reachable from the internet, nothing better available): self-host: unreachable; s3 is not configured",
                o.describe());
    }

    @Test
    void failedCheckIsDescribedAsSuch() throws Exception {
        Map<String, UploadChain.Step> steps = Map.of("self-host",
                checkFails("http://localhost/pack", "local check failed: HTTP 503"));
        UploadChain.Outcome o = UploadChain.run(List.of("self-host"), steps::get, FILE, "abc");
        assertTrue(o.describe().startsWith("self-host (failed its reachability check, nothing better available)"));
    }

    @Test
    void degradedSelfHostBeatsFailingFallback() throws Exception {
        Map<String, UploadChain.Step> steps = Map.of(
                "self-host", checkFails("http://localhost/pack", "unreachable"),
                "s3", failing("403 Forbidden"));
        UploadChain.Outcome o = UploadChain.run(List.of("self-host", "s3"), steps::get, FILE, "abc");
        assertEquals("http://localhost/pack", o.url());
        assertTrue(o.degraded());
    }

    @Test
    void everythingFailingThrowsThePrimaryError() {
        Map<String, UploadChain.Step> steps = Map.of("self-host", failing("not bound"), "s3", failing("403"));
        IOException e = assertThrows(IOException.class,
                () -> UploadChain.run(List.of("self-host", "s3"), steps::get, FILE, "abc"));
        assertEquals("not bound", e.getMessage());
    }

    @Test
    void nothingConfiguredThrows() {
        assertThrows(IllegalStateException.class, () -> UploadChain.run(List.of("s3"), n -> null, FILE, "abc"));
    }

    @Test
    void onlyTheChosenStepIsCommitted() throws Exception {
        List<String> committed = new ArrayList<>();
        class Recording implements UploadChain.Step {
            final String name; final String problem;
            Recording(String name, String problem) { this.name = name; this.problem = problem; }
            @Override public String upload(File f, String h) { return "http://" + name; }
            @Override public String check(String url) { return problem; }
            @Override public void commit() { committed.add(name); }
        }
        Map<String, UploadChain.Step> steps = Map.of(
                "self-host", new Recording("self-host", "unreachable"), "s3", new Recording("s3", null));
        UploadChain.run(List.of("self-host", "s3"), steps::get, FILE, "abc");
        assertEquals(List.of("s3"), committed);

        committed.clear();
        UploadChain.run(List.of("self-host"), steps::get, FILE, "abc");
        assertEquals(List.of("self-host"), committed, "a degraded self-host is still committed");
    }

    @Test
    void verificationTokenIsAppendedAsQueryParameter() {
        assertEquals("http://h/pack?pm-verify=t", SelfHostProvider.withToken("http://h/pack", "t"));
        assertEquals("http://h/rp.zip?v=1&pm-verify=t", SelfHostProvider.withToken("http://h/rp.zip?v=1", "t"));
        assertEquals("http://h/pack?pm-verify=t#x", SelfHostProvider.withToken("http://h/pack#x", "t"));
    }
}
