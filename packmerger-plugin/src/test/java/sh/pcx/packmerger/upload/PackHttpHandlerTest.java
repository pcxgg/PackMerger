package sh.pcx.packmerger.upload;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.pcx.packmerger.upload.PackHttpHandler.ByteRange;
import sh.pcx.packmerger.upload.PackHttpHandler.ServedPack;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PackHttpHandlerTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @TempDir Path dir;
    private HttpServer server;
    private HttpClient client;
    private final AtomicReference<ServedPack> served = new AtomicReference<>();
    private final AtomicReference<Semaphore> limiter = new AtomicReference<>();
    private byte[] content;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        content = new byte[50_000];
        new Random(42).nextBytes(content);
        File file = dir.resolve("pack.zip").toFile();
        Files.write(file.toPath(), content);
        served.set(new ServedPack(file, SHA));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new PackHttpHandler(q -> served.get(), limiter::get, () -> Set.of("/pack", "/cdn/rp.zip")));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        client.close();
    }

    private HttpResponse<byte[]> send(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void getServesWholePackWithStrongEtag() throws Exception {
        HttpResponse<byte[]> r = send("GET", "/pack");
        assertEquals(200, r.statusCode());
        assertArrayEquals(content, r.body());
        assertEquals("\"" + SHA + "\"", r.headers().firstValue("ETag").orElseThrow());
        assertEquals("bytes", r.headers().firstValue("Accept-Ranges").orElseThrow());
        assertEquals("application/zip", r.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-cache", r.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void exactPathMatching() throws Exception {
        assertEquals(404, send("GET", "/packfoo").statusCode());
        assertEquals(404, send("GET", "/pack/").statusCode());
        assertEquals(404, send("GET", "/").statusCode());
        assertEquals(200, send("GET", "/cdn/rp.zip").statusCode(), "the public-url path is served too");
        assertEquals(200, send("GET", "/pack?v=1").statusCode(), "query strings are ignored");
    }

    @Test
    void headReturnsHeadersOnly() throws Exception {
        HttpResponse<byte[]> r = send("HEAD", "/pack");
        assertEquals(200, r.statusCode());
        assertEquals(0, r.body().length);
        assertEquals(String.valueOf(content.length), r.headers().firstValue("Content-Length").orElseThrow());
        assertEquals("\"" + SHA + "\"", r.headers().firstValue("ETag").orElseThrow());
    }

    @Test
    void otherMethodsAre405() throws Exception {
        HttpResponse<byte[]> r = send("POST", "/pack");
        assertEquals(405, r.statusCode());
        assertEquals("GET, HEAD", r.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void ifNoneMatchGives304() throws Exception {
        HttpResponse<byte[]> r = send("GET", "/pack", "If-None-Match", "\"" + SHA + "\"");
        assertEquals(304, r.statusCode());
        assertEquals(0, r.body().length);
        assertEquals(304, send("GET", "/pack", "If-None-Match", "\"other\", W/\"" + SHA + "\"").statusCode());
        assertEquals(304, send("HEAD", "/pack", "If-None-Match", "*").statusCode());
        assertEquals(200, send("GET", "/pack", "If-None-Match", "\"stale\"").statusCode());
    }

    @Test
    void rangeGives206() throws Exception {
        HttpResponse<byte[]> r = send("GET", "/pack", "Range", "bytes=0-1023");
        assertEquals(206, r.statusCode());
        assertArrayEquals(Arrays.copyOfRange(content, 0, 1024), r.body());
        assertEquals("bytes 0-1023/" + content.length, r.headers().firstValue("Content-Range").orElseThrow());

        HttpResponse<byte[]> tail = send("GET", "/pack", "Range", "bytes=-100");
        assertEquals(206, tail.statusCode());
        assertArrayEquals(Arrays.copyOfRange(content, content.length - 100, content.length), tail.body());

        HttpResponse<byte[]> open = send("GET", "/pack", "Range", "bytes=49990-");
        assertArrayEquals(Arrays.copyOfRange(content, 49990, content.length), open.body());

        HttpResponse<byte[]> head = send("HEAD", "/pack", "Range", "bytes=10-19");
        assertEquals(206, head.statusCode());
        assertEquals("10", head.headers().firstValue("Content-Length").orElseThrow());
    }

    @Test
    void unsatisfiableRangeGives416() throws Exception {
        HttpResponse<byte[]> r = send("GET", "/pack", "Range", "bytes=60000-");
        assertEquals(416, r.statusCode());
        assertEquals("bytes */" + content.length, r.headers().firstValue("Content-Range").orElseThrow());
    }

    @Test
    void ifRangeMismatchServesWholeFile() throws Exception {
        HttpResponse<byte[]> r = send("GET", "/pack", "Range", "bytes=0-9", "If-Range", "\"old\"");
        assertEquals(200, r.statusCode());
        assertEquals(content.length, r.body().length);
        assertEquals(206, send("GET", "/pack", "Range", "bytes=0-9", "If-Range", "\"" + SHA + "\"").statusCode());
    }

    @Test
    void noPackGives503() throws Exception {
        served.set(null);
        assertEquals(503, send("GET", "/pack").statusCode());
        served.set(new ServedPack(dir.resolve("missing.zip").toFile(), SHA));
        assertEquals(503, send("GET", "/pack").statusCode());
    }

    @Test
    void rateLimiterStillApplies() throws Exception {
        limiter.set(new Semaphore(0));
        assertEquals(429, send("GET", "/pack").statusCode());
        assertEquals(200, send("HEAD", "/pack").statusCode(), "HEAD does not need a download slot");
        assertEquals(304, send("GET", "/pack", "If-None-Match", "\"" + SHA + "\"").statusCode());
        limiter.set(new Semaphore(1));
        assertEquals(200, send("GET", "/pack").statusCode());
        assertEquals(1, limiter.get().availablePermits(), "permit released after the download");
    }

    @Test
    void inFlightDownloadSurvivesSwapAndDelete() throws Exception {
        // Large enough that the response is still streaming when we swap and delete.
        byte[] big = new byte[8 * 1024 * 1024];
        new Random(7).nextBytes(big);
        File bigFile = dir.resolve("big.zip").toFile();
        Files.write(bigFile.toPath(), big);
        served.set(new ServedPack(bigFile, SHA));

        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/pack")).build();
        HttpResponse<InputStream> r = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            byte[] first = in.readNBytes(1024);
            File other = dir.resolve("other.zip").toFile();
            Files.write(other.toPath(), new byte[] {1, 2, 3});
            served.set(new ServedPack(other, "ffff"));
            try {
                Files.delete(bigFile.toPath());
            } catch (java.io.IOException windows) {
                // Windows refuses to delete an open file; the swap alone is still covered.
            }
            byte[] rest = in.readAllBytes();
            byte[] all = new byte[first.length + rest.length];
            System.arraycopy(first, 0, all, 0, first.length);
            System.arraycopy(rest, 0, all, first.length, rest.length);
            assertArrayEquals(big, all);
        }
        assertArrayEquals(new byte[] {1, 2, 3}, send("GET", "/pack").body());
    }

    @Test
    void probePassesForServedPackAndFailsOtherwise() throws Exception {
        File pack = served.get().file();
        assertTrue(ReachabilityProbe.probe(base + "/pack", pack, Duration.ofSeconds(5)).ok());

        File different = dir.resolve("different.zip").toFile();
        Files.write(different.toPath(), new byte[] {9, 9, 9, 9});
        ReachabilityProbe.Result wrong = ReachabilityProbe.probe(base + "/pack", different, Duration.ofSeconds(5));
        assertFalse(wrong.ok());

        ReachabilityProbe.Result notFound = ReachabilityProbe.probe(base + "/nope", pack, Duration.ofSeconds(5));
        assertFalse(notFound.ok());
        assertTrue(notFound.detail().contains("HTTP 404"), notFound.detail());

        // Nothing listens on port 1 (needs root to bind).
        ReachabilityProbe.Result refused = ReachabilityProbe.probe("http://127.0.0.1:1/pack", pack, Duration.ofSeconds(2));
        assertFalse(refused.ok());
        assertTrue(refused.detail().contains("could not connect") || refused.detail().contains("timed out"),
                refused.detail());
    }

    @Test
    void parseRanges() {
        assertEquals(new ByteRange(0, 99), ByteRange.parse("bytes=0-99", 1000));
        assertEquals(new ByteRange(900, 999), ByteRange.parse("bytes=900-5000", 1000));
        assertEquals(new ByteRange(500, 999), ByteRange.parse("bytes=500-", 1000));
        assertEquals(new ByteRange(990, 999), ByteRange.parse("bytes=-10", 1000));
        assertEquals(new ByteRange(0, 999), ByteRange.parse("bytes=-5000", 1000));
        assertSame(ByteRange.UNSATISFIABLE, ByteRange.parse("bytes=1000-", 1000));
        assertSame(ByteRange.UNSATISFIABLE, ByteRange.parse("bytes=-0", 1000));
        assertNull(ByteRange.parse("bytes=0-1,5-6", 1000), "multiple ranges: serve the whole file");
        assertNull(ByteRange.parse("items=0-1", 1000));
        assertNull(ByteRange.parse("bytes=5-1", 1000));
        assertNull(ByteRange.parse("bytes=a-b", 1000));
        assertNull(ByteRange.parse(null, 1000));
    }

    @Test
    void packIsChosenPerRequestQuery() throws Exception {
        File staged = dir.resolve("staged.zip").toFile();
        Files.write(staged.toPath(), new byte[] {7, 7, 7});
        server.removeContext("/");
        server.createContext("/", new PackHttpHandler(
                q -> q != null && q.contains("pm-verify=tok") ? new ServedPack(staged, "eeee") : served.get(),
                limiter::get, () -> Set.of("/pack")));
        assertArrayEquals(content, send("GET", "/pack").body());
        assertArrayEquals(new byte[] {7, 7, 7}, send("GET", "/pack?pm-verify=tok").body());
    }
}
