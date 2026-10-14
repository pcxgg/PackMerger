package sh.pcx.packmerger.upload;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Works out the URL players are told to download the self-hosted pack from.
 *
 * <p>Resolution order (first match wins):</p>
 * <ol>
 *   <li>{@code upload.self-host.public-url}: a complete URL, used as-is. Only when its
 *       path is empty (or {@code /}) is {@code /pack} appended.</li>
 *   <li>{@code upload.self-host.public-host}: host name or IP, combined with the port.</li>
 *   <li>The public IP found by the opt-in lookup ({@code detect-public-ip}).</li>
 *   <li>{@code server-ip} from {@code server.properties}.</li>
 *   <li>The address the HTTP server is bound to (the wildcard address, which nobody can
 *       connect to, is advertised as {@code localhost}).</li>
 * </ol>
 *
 * <p>Steps 2-5 are candidates: the first one that is publicly routable wins. If none is,
 * the first non-blank candidate is used anyway (a LAN address beats {@code localhost})
 * and the resolution is flagged as non-routable, so the operator is told exactly what to
 * set. Pure logic, no I/O: the caller supplies the candidates.</p>
 */
public final class SelfHostAddress {

    /** The path the built-in HTTP server serves the pack on. */
    public static final String PACK_PATH = "/pack";

    private SelfHostAddress() {}

    /** Where a resolved host came from, with the config key an operator would change. */
    public enum Source {
        PUBLIC_URL("upload.self-host.public-url"),
        PUBLIC_HOST("upload.self-host.public-host"),
        DETECTED_PUBLIC_IP("public IP lookup (upload.self-host.detect-public-ip)"),
        SERVER_IP("server-ip in server.properties"),
        BIND_ADDRESS("bind address (nothing configured)");

        private final String description;

        Source(String description) { this.description = description; }

        /** @return a human-readable description naming the setting involved */
        public String description() { return description; }
    }

    /** One possible host for the auto-built URL. {@code host} may be blank (= not set). */
    public record Candidate(Source source, String host) {}

    /**
     * The result of resolving the advertised URL.
     *
     * @param url      the URL players will be sent
     * @param host     the host part of that URL
     * @param source   where the host came from
     * @param routable {@code false} if the host is loopback, private, link-local, ULA,
     *                 CGNAT or the wildcard address, i.e. players outside the local
     *                 network cannot reach it
     */
    public record Resolution(String url, String host, Source source, boolean routable) {

        /** @return what the operator should set when the host is not routable, or {@code null} */
        public String advice() {
            if (routable) return null;
            if (source == Source.PUBLIC_URL) {
                return "upload.self-host.public-url points at " + host + ", which players outside your network"
                        + " cannot reach. Set it to the public address of this server (e.g. http://play.example.com:"
                        + "<port>/pack or your reverse-proxy URL).";
            }
            return "The self-host URL " + url + " uses " + host + " (from " + source.description()
                    + "), which players outside your network cannot reach. Set upload.self-host.public-url"
                    + " (full URL) or upload.self-host.public-host (host name or public IP) in config.yml,"
                    + " or enable upload.self-host.detect-public-ip.";
        }
    }

    /**
     * Resolves the advertised URL.
     *
     * @param publicUrl  the configured {@code public-url} (blank = auto)
     * @param candidates host candidates in priority order (steps 2-5 above)
     * @param port       the self-host port, used for auto-built URLs
     */
    public static Resolution resolve(String publicUrl, List<Candidate> candidates, int port) {
        if (publicUrl != null && !publicUrl.isBlank()) {
            String url = normalizePublicUrl(publicUrl.trim());
            String host = hostOf(url);
            return new Resolution(url, host, Source.PUBLIC_URL, !isNonRoutable(host));
        }
        Candidate firstNonBlank = null;
        for (Candidate c : candidates) {
            if (c.host() == null || c.host().isBlank()) continue;
            String host = c.host().trim();
            if (firstNonBlank == null) firstNonBlank = new Candidate(c.source(), host);
            if (!isNonRoutable(host)) {
                return new Resolution(buildUrl(host, port), host, c.source(), true);
            }
        }
        if (firstNonBlank == null) firstNonBlank = new Candidate(Source.BIND_ADDRESS, "localhost");
        String host = firstNonBlank.host();
        // The wildcard address is a bind address, not something a client can connect to.
        if (isWildcard(host)) host = "localhost";
        return new Resolution(buildUrl(host, port), host, firstNonBlank.source(), false);
    }

    /**
     * Treats {@code public-url} as a complete URL. {@code /pack} is appended only when the
     * URL has no path at all ({@code http://host:port} or {@code http://host:port/}); a URL
     * with any other path (a reverse proxy, a CDN file name, {@code .../pack}) is kept as-is.
     */
    public static String normalizePublicUrl(String url) {
        String path;
        try {
            path = URI.create(url).getRawPath();
        } catch (IllegalArgumentException e) {
            return url; // malformed: leave it alone, the reachability check reports it
        }
        if (path != null && !path.isEmpty() && !path.equals("/")) return url;
        // Insert /pack where the (empty or "/") path is, keeping any query string.
        int authorityStart = url.indexOf("//");
        int from = authorityStart < 0 ? 0 : authorityStart + 2;
        int end = url.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int i = url.indexOf(c, from);
            if (i >= 0 && i < end) end = i;
        }
        String rest = url.substring(end);
        if (rest.startsWith("/")) rest = rest.substring(1);
        return url.substring(0, end) + PACK_PATH + rest;
    }

    /** @return the path a request for {@code url} arrives with, or {@code null} if it cannot be parsed */
    public static String pathOf(String url) {
        try {
            String path = URI.create(url).getRawPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Builds {@code http://host:port/pack}, bracketing IPv6 literals. */
    public static String buildUrl(String host, int port) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port + PACK_PATH;
    }

    /** @return the host of {@code url} (brackets stripped), or the whole string if unparseable */
    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return url;
            return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private static boolean isWildcard(String host) {
        String h = strip(host);
        return h.equals("0.0.0.0") || h.equals("::") || h.equals("0:0:0:0:0:0:0:0");
    }

    /**
     * @return {@code true} if players on the internet cannot connect to {@code host}:
     *         blank, {@code localhost}, the wildcard address, loopback (127/8, ::1),
     *         RFC 1918 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10),
     *         CGNAT (100.64/10) or IPv6 unique-local (fc00::/7). Host names other than
     *         {@code localhost} are assumed routable (no DNS lookup is made).
     */
    public static boolean isNonRoutable(String host) {
        if (host == null || host.isBlank()) return true;
        String h = strip(host);
        if (h.equals("localhost") || h.endsWith(".localhost")) return true;
        if (IPV4.matcher(h).matches()) {
            String[] parts = h.split("\\.");
            int[] o = new int[4];
            for (int i = 0; i < 4; i++) {
                o[i] = Integer.parseInt(parts[i]);
                if (o[i] > 255) return false; // not an IP literal; treat as a name
            }
            return o[0] == 0 || o[0] == 127 || o[0] == 10
                    || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                    || (o[0] == 192 && o[1] == 168)
                    || (o[0] == 169 && o[1] == 254)
                    || (o[0] == 100 && o[1] >= 64 && o[1] <= 127);
        }
        if (h.indexOf(':') >= 0 && IPV6_CHARS.matcher(h).matches()) {
            try {
                // A literal containing only hex digits, ':' and '.' is parsed without DNS.
                InetAddress a = InetAddress.getByName(h);
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress()) {
                    return true;
                }
                byte[] b = a.getAddress();
                if (b.length == 4) return isNonRoutable(a.getHostAddress()); // IPv4-mapped
                return (b[0] & 0xfe) == 0xfc; // fc00::/7 unique local
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }

    private static String strip(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) h = h.substring(1, h.length() - 1);
        int zone = h.indexOf('%');
        return zone >= 0 ? h.substring(0, zone) : h;
    }
}
