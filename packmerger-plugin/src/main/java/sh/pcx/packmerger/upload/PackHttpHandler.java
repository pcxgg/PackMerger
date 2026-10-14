package sh.pcx.packmerger.upload;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Serves the published pack over HTTP for {@link SelfHostProvider}.
 *
 * <ul>
 *   <li>Exact path matching: only the configured paths ({@code /pack}, plus the path of
 *       {@code public-url} for reverse proxies that forward it unchanged) are served;
 *       anything else is 404. ({@code HttpServer} contexts are prefix matches.)</li>
 *   <li>{@code GET} and {@code HEAD}; other methods get 405 with {@code Allow}.</li>
 *   <li>Strong ETag ({@code "<sha1>"}), {@code If-None-Match} answered with 304.</li>
 *   <li>A single byte range ({@code Range: bytes=a-b}, {@code a-}, {@code -n}) answered with
 *       206, unsatisfiable ranges with 416, honouring {@code If-Range}.</li>
 *   <li>Body-bearing responses take a permit from the download limiter (429 when none is
 *       free); HEAD and 304 are cheap and don't.</li>
 *   <li>The file is opened once at the start of the request and streamed from that open
 *       channel, so publishing a new pack (and deleting an old snapshot) mid-download
 *       cannot break or corrupt an in-flight transfer.</li>
 * </ul>
 */
public final class PackHttpHandler implements HttpHandler {

    /** The pack currently served: the file and its lowercase hex SHA-1. */
    public record ServedPack(File file, String sha1Hex) {}

    private final Function<String, ServedPack> current;
    private final Supplier<Semaphore> limiter;
    private final Supplier<Set<String>> paths;

    /**
     * @param current returns the pack to serve for a request's raw query string (which may be
     *                {@code null}); {@code null} = none yet, 503
     * @param limiter supplies the download limiter ({@code null} = unlimited)
     * @param paths   supplies the exact request paths that serve the pack
     */
    public PackHttpHandler(Function<String, ServedPack> current, Supplier<Semaphore> limiter,
                           Supplier<Set<String>> paths) {
        this.current = current;
        this.limiter = limiter;
        this.paths = paths;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            serve(exchange);
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        if (path == null || !paths.get().contains(path)) {
            sendText(exchange, 404, "Not found");
            return;
        }
        String method = exchange.getRequestMethod();
        boolean head = "HEAD".equalsIgnoreCase(method);
        if (!head && !"GET".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendText(exchange, 405, "Method not allowed");
            return;
        }

        ServedPack pack = current.apply(exchange.getRequestURI().getRawQuery());
        FileChannel channel = null;
        if (pack != null) {
            try {
                channel = FileChannel.open(pack.file().toPath(), StandardOpenOption.READ);
            } catch (NoSuchFileException e) {
                channel = null;
            }
        }
        if (channel == null) {
            exchange.getResponseHeaders().set("Retry-After", "10");
            sendText(exchange, 503, "Resource pack not available yet");
            return;
        }

        try (FileChannel ch = channel) {
            long size = ch.size();
            String etag = "\"" + pack.sha1Hex() + "\"";
            Headers h = exchange.getResponseHeaders();
            h.set("ETag", etag);
            h.set("Accept-Ranges", "bytes");
            // The URL stays the same while the content changes: caches (CDNs) must revalidate.
            h.set("Cache-Control", "no-cache");

            Headers req = exchange.getRequestHeaders();
            if (matchesAny(req.getFirst("If-None-Match"), etag)) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }

            h.set("Content-Type", "application/zip");
            h.set("Content-Disposition", "attachment; filename=\"resource-pack.zip\"");

            long start = 0;
            long length = size;
            int status = 200;
            String rangeHeader = req.getFirst("Range");
            String ifRange = req.getFirst("If-Range");
            if (rangeHeader != null && (ifRange == null || ifRange.trim().equals(etag))) {
                ByteRange range = ByteRange.parse(rangeHeader, size);
                if (range == ByteRange.UNSATISFIABLE) {
                    h.set("Content-Range", "bytes */" + size);
                    sendText(exchange, 416, "Requested range not satisfiable");
                    return;
                }
                if (range != null) {
                    start = range.start();
                    length = range.end() - range.start() + 1;
                    status = 206;
                    h.set("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + size);
                }
            }

            if (head) {
                h.set("Content-Length", Long.toString(length));
                exchange.sendResponseHeaders(status, -1);
                return;
            }

            // Capture the limiter so a reconfigure mid-download releases the permit on the
            // semaphore it came from.
            Semaphore permits = limiter.get();
            if (permits != null && !permits.tryAcquire()) {
                h.remove("Content-Range");
                h.remove("Content-Disposition");
                sendText(exchange, 429, "Too many concurrent downloads, try again later");
                return;
            }
            try {
                exchange.sendResponseHeaders(status, length);
                if (length == 0) return;
                try (OutputStream os = exchange.getResponseBody()) {
                    ByteBuffer buffer = ByteBuffer.allocate(16384);
                    long position = start;
                    long remaining = length;
                    while (remaining > 0) {
                        buffer.clear();
                        if (remaining < buffer.capacity()) buffer.limit((int) remaining);
                        int read = ch.read(buffer, position);
                        if (read < 0) break;
                        os.write(buffer.array(), 0, read);
                        position += read;
                        remaining -= read;
                    }
                }
            } finally {
                if (permits != null) permits.release();
            }
        }
    }

    /** {@code If-None-Match} uses weak comparison: {@code W/"x"} matches {@code "x"}; {@code *} matches anything. */
    static boolean matchesAny(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (c.equals("*")) return true;
            if (c.startsWith("W/")) c = c.substring(2);
            if (c.equals(etag)) return true;
        }
        return false;
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * A single, satisfiable byte range {@code [start, end]} (inclusive).
     *
     * <p>{@link #parse} returns {@code null} when the header should be ignored (malformed,
     * not {@code bytes}, or several ranges: serving the whole file is always allowed) and
     * {@link #UNSATISFIABLE} when the range lies entirely outside the file.</p>
     */
    public record ByteRange(long start, long end) {

        /** Marker for a syntactically valid range that does not overlap the file (416). */
        public static final ByteRange UNSATISFIABLE = new ByteRange(-1, -1);

        public static ByteRange parse(String header, long size) {
            if (header == null) return null;
            String h = header.trim();
            if (!h.regionMatches(true, 0, "bytes=", 0, 6)) return null;
            String spec = h.substring(6).trim();
            if (spec.contains(",")) return null;
            int dash = spec.indexOf('-');
            if (dash < 0) return null;
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            try {
                if (a.isEmpty()) {
                    // Suffix range: the last n bytes.
                    if (b.isEmpty()) return null;
                    long n = Long.parseLong(b);
                    if (n < 0) return null;
                    if (n == 0 || size == 0) return UNSATISFIABLE;
                    return new ByteRange(Math.max(0, size - n), size - 1);
                }
                long start = Long.parseLong(a);
                if (start < 0) return null;
                long end = b.isEmpty() ? Long.MAX_VALUE : Long.parseLong(b);
                if (end < start) return null;
                if (start >= size) return UNSATISFIABLE;
                return new ByteRange(start, Math.min(end, size - 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
