package sh.pcx.packmerger.upload;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Opt-in public IP lookup for the self-host URL ({@code upload.self-host.detect-public-ip}).
 *
 * <p>This contacts a third-party service (by default Amazon's {@code checkip} and
 * {@code ipify}), which learns this server's IP address. It is therefore off by default and
 * disclosed in config.yml. The services are tried in order; the first one that answers with
 * a publicly routable IP literal wins. Blocking; callers run it off the main thread.</p>
 */
public final class PublicIpDetector {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:]+(\\.\\d{1,3}){0,4}");

    private PublicIpDetector() {}

    /**
     * @param services lookup URLs answering with the caller's IP as plain text
     * @param timeout  per-service timeout
     * @param log      receives one line per failed service
     * @return the detected IP, or {@code null} if no service gave a usable answer
     */
    public static String detect(List<String> services, Duration timeout, Consumer<String> log) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try (client) {
            for (String service : services) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(service.trim()))
                            .timeout(timeout)
                            .header("User-Agent", "PackMerger")
                            .GET().build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        log.accept(service + " answered HTTP " + response.statusCode());
                        continue;
                    }
                    String ip = parse(response.body());
                    if (ip == null) {
                        log.accept(service + " did not answer with a public IP address");
                        continue;
                    }
                    return ip;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    log.accept(service + " failed: " + e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * @return the IP literal in {@code body} (trimmed) if it is one and is publicly routable,
     *         otherwise {@code null}
     */
    static String parse(String body) {
        if (body == null) return null;
        String ip = body.trim();
        if (ip.isEmpty() || ip.length() > 45) return null;
        boolean literal = IPV4.matcher(ip).matches() || (ip.indexOf(':') >= 0 && IPV6.matcher(ip).matches());
        if (!literal) return null;
        if (IPV4.matcher(ip).matches()) {
            for (String part : ip.split("\\.")) {
                if (Integer.parseInt(part) > 255) return null;
            }
        }
        return SelfHostAddress.isNonRoutable(ip) ? null : ip;
    }
}
