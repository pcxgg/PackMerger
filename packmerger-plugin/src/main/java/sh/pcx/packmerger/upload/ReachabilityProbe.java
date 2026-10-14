package sh.pcx.packmerger.upload;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;

/**
 * Checks that a URL really serves a given pack, by downloading only its first KiB
 * ({@code Range: bytes=0-1023}) and comparing the bytes with the file on disk.
 *
 * <p>An HTML error page, a captive portal, a different pack or a redirect all fail the
 * check, unlike a plain "did it answer 200" probe. A server that ignores {@code Range}
 * (200 with the whole file) passes as long as the first bytes match; only that prefix is
 * read. Blocking; callers run it off the main thread.</p>
 */
public final class ReachabilityProbe {

    /** Bytes compared. */
    static final int PROBE_BYTES = 1024;

    private ReachabilityProbe() {}

    /** @param ok whether the URL served the expected bytes; {@code detail} explains a failure */
    public record Result(boolean ok, String detail) {
        static Result pass() { return new Result(true, "ok"); }
        static Result fail(String detail) { return new Result(false, detail); }
    }

    /**
     * @param url     the URL to fetch
     * @param pack    the file the URL is expected to serve
     * @param timeout connect and overall request timeout
     */
    public static Result probe(String url, File pack, Duration timeout) {
        byte[] expected;
        long packLength;
        try {
            packLength = Files.size(pack.toPath());
            try (InputStream in = Files.newInputStream(pack.toPath())) {
                expected = in.readNBytes((int) Math.min(PROBE_BYTES, packLength));
            }
        } catch (IOException e) {
            return Result.fail("cannot read the pack file: " + e.getMessage());
        }
        if (expected.length == 0) return Result.fail("the pack file is empty");

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Result.fail("malformed URL " + url);
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try (client) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Range", "bytes=0-" + (expected.length - 1))
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "PackMerger reachability check")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int code = response.statusCode();
                if (code != 200 && code != 206) {
                    return Result.fail("HTTP " + code + " from " + url);
                }
                if (code == 206) {
                    String expectedRange = "bytes 0-" + (expected.length - 1) + "/" + packLength;
                    String range = response.headers().firstValue("Content-Range").orElse("");
                    if (!expectedRange.equals(range)) {
                        return Result.fail("unexpected Content-Range '" + range + "' from " + url
                                + " (expected '" + expectedRange + "')");
                    }
                }
                byte[] actual = body.readNBytes(expected.length);
                if (!Arrays.equals(expected, actual)) {
                    return Result.fail(url + " answered, but with different content than the pack"
                            + " (another service on that address, a proxy error page, or a stale cache)");
                }
                return Result.pass();
            }
        } catch (HttpTimeoutException e) {
            return Result.fail("timed out after " + timeout.toSeconds() + " s fetching " + url);
        } catch (java.net.ConnectException e) {
            return Result.fail("could not connect to " + url + " (connection refused or unreachable)");
        } catch (IOException e) {
            String msg = e.getMessage();
            return Result.fail("could not fetch " + url + " ("
                    + (msg == null || msg.isBlank() ? "I/O error" : msg) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("interrupted");
        }
    }
}
