package sh.pcx.packmerger.upload;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Picks the self-host HTTP port and explains anything suspicious about it.
 *
 * <p>{@code upload.self-host.port: -1} (the default since config-version 4) means
 * "Minecraft port + 1", which on hosting panels is usually the next allocation. An
 * out-of-range port falls back to the same derived port. A port that equals the
 * Minecraft port, {@code rcon.port} (when RCON is enabled) or {@code query.port} (when
 * query is enabled) is kept but reported, because the bind will fail or steal traffic.
 * Pure logic: the caller passes the values from {@code server.properties}.</p>
 *
 * @param port     the port to bind
 * @param derived  {@code true} if the port was derived from the Minecraft port
 * @param warnings human-readable problems to log on start (may be empty)
 */
public record SelfHostPort(int port, boolean derived, List<String> warnings) {

    /**
     * @param configured  {@code upload.self-host.port} ({@code <= 0} = derive)
     * @param minecraftPort the server port ({@code Bukkit.getPort()})
     * @param serverProperties {@code server.properties} (may be empty, never {@code null})
     */
    public static SelfHostPort resolve(int configured, int minecraftPort, Properties serverProperties) {
        List<String> warnings = new ArrayList<>();
        int derivedPort = minecraftPort + 1 <= 65535 ? minecraftPort + 1 : minecraftPort - 1;
        int port;
        boolean derived;
        if (configured <= 0) {
            port = derivedPort;
            derived = true;
        } else if (configured > 65535) {
            warnings.add("upload.self-host.port " + configured + " is not a valid TCP port (1-65535); using "
                    + derivedPort + " (Minecraft port + 1) instead.");
            port = derivedPort;
            derived = true;
        } else {
            port = configured;
            derived = false;
        }

        if (port == minecraftPort) {
            warnings.add("upload.self-host.port " + port + " is the Minecraft server port; the HTTP server cannot"
                    + " bind it. Use -1 (Minecraft port + 1) or another free port.");
        }
        if (enabled(serverProperties, "enable-rcon")) {
            Integer rcon = intProperty(serverProperties, "rcon.port");
            if (rcon != null && rcon == port) {
                warnings.add("upload.self-host.port " + port + " collides with rcon.port in server.properties."
                        + " Change one of them (the HTTP server or RCON will fail to bind).");
            }
        }
        if (enabled(serverProperties, "enable-query")) {
            Integer query = intProperty(serverProperties, "query.port");
            if (query != null && query == port) {
                warnings.add("upload.self-host.port " + port + " is also query.port in server.properties."
                        + " Query uses UDP so both can bind, but firewall/panel rules for the port may only allow"
                        + " one protocol; prefer a separate port.");
            }
        }
        return new SelfHostPort(port, derived, List.copyOf(warnings));
    }

    private static boolean enabled(Properties props, String key) {
        return Boolean.parseBoolean(props.getProperty(key, "false").trim());
    }

    private static Integer intProperty(Properties props, String key) {
        String v = props.getProperty(key);
        if (v == null) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
