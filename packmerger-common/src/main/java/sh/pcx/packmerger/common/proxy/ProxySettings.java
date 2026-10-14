package sh.pcx.packmerger.common.proxy;

import sh.pcx.packmerger.common.PackInfo;
import sh.pcx.packmerger.common.Secrets;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The proxy plugin's {@code config.properties} (shared by Velocity and BungeeCord).
 *
 * @param staticPack     the pack from the {@code url}/{@code sha1} keys (may have no URL)
 * @param required       the client "required" flag
 * @param prompt         plain-text prompt, or empty
 * @param secret         resolved shared secret, or {@code null} when none is set
 * @param secretRaw      the secret as written (for diagnostics, never logged)
 * @param mode           one network pack, or each backend's own pack
 * @param acceptFrom     NETWORK mode: proxy server names allowed to publish (empty = any)
 * @param resendOnUpdate whether to offer an updated pack to players already online
 */
public record ProxySettings(PackInfo staticPack, boolean required, String prompt, String secret,
                            String secretRaw, Mode mode, Set<String> acceptFrom, boolean resendOnUpdate) {

    /** File name inside the proxy plugin's data folder. */
    public static final String FILE_NAME = "config.properties";

    /** What the proxy offers. */
    public enum Mode {
        /** One pack for the whole network: the latest one a backend published (or {@code url}). */
        NETWORK,
        /** Each backend's own pack, offered when a player connects to that backend. */
        PER_SERVER;

        static Mode parse(String raw, Consumer<String> warn) {
            String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "", "network" -> NETWORK;
                case "per-server", "per_server", "perserver" -> PER_SERVER;
                default -> {
                    warn.accept("unknown mode '" + raw + "' in " + FILE_NAME + "; using 'network'");
                    yield NETWORK;
                }
            };
        }

        public String key() {
            return this == NETWORK ? "network" : "per-server";
        }
    }

    /** Every key with its default and the comment written above it. Order = file order. */
    private static final Map<String, String[]> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("url", new String[]{"",
                "Download URL of the pack to offer until a backend publishes one (optional).\n"
                        + "Backends with distribution.proxy-notify: true push their pack here automatically;\n"
                        + "the last pushed pack is remembered in state.properties across proxy restarts.\n"
                        + "Editing url/sha1 here takes over again on the next proxy start."});
        KEYS.put("sha1", new String[]{"", "SHA-1 (hex) of the pack at url (optional, but clients re-download without it)."});
        KEYS.put("required", new String[]{"false", "Send the pack with the client's 'required' flag (true/false)."});
        KEYS.put("prompt", new String[]{"", "Plain-text prompt shown with the pack offer (empty = Minecraft's default)."});
        KEYS.put("secret", new String[]{"",
                "Shared secret that authenticates pack messages between backends and this proxy\n"
                        + "(HMAC-SHA256). Use the same value as distribution.proxy-secret in every backend's\n"
                        + "PackMerger config.yml. ${NAME} reads an environment variable, e.g. secret=${PACKMERGER_SECRET}.\n"
                        + "When set, unsigned or wrongly signed messages are rejected. When empty, any backend\n"
                        + "can replace the pack. BungeeCord needs it: Bungee has no resource-pack API, so the\n"
                        + "proxy asks the backend to send the pack, and backends only accept signed requests."});
        KEYS.put("mode", new String[]{"network",
                "network    = one pack for the whole network: the latest one a backend published.\n"
                        + "per-server = each backend's own pack, offered when a player connects to that backend\n"
                        + "             (backends without one get url above). The pack replaces the previous\n"
                        + "             server's pack in place instead of stacking."});
        KEYS.put("accept-from", new String[]{"",
                "network mode: comma-separated proxy server names allowed to publish the network pack\n"
                        + "(empty = any backend). Set it when several backends run PackMerger, so they don't\n"
                        + "overwrite each other's pack."});
        KEYS.put("resend-on-update", new String[]{"true",
                "Offer an updated pack to players who are already online (true/false). The pack keeps\n"
                        + "the same id, so the client swaps it in place."});
    }

    /** Loads {@code dir/config.properties} against the real environment. */
    public static ProxySettings load(Path dir, Consumer<String> warn) {
        return load(dir, warn, System::getenv);
    }

    /**
     * Loads {@code dir/config.properties}, creating it with comments if it is missing and
     * appending (with comments) any key an older file doesn't have yet.
     */
    public static ProxySettings load(Path dir, Consumer<String> warn, Function<String, String> env) {
        Path file = dir.resolve(FILE_NAME);
        Properties props = new Properties();
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(in);
                }
            }
            StringBuilder missing = new StringBuilder();
            if (!Files.exists(file)) {
                missing.append("# PackMerger proxy plugin (Velocity / BungeeCord).\n")
                        .append("# Docs: https://github.com/PCX-Network/PackMerger#proxy-velocity--bungeecord\n");
            }
            for (Map.Entry<String, String[]> e : KEYS.entrySet()) {
                if (props.containsKey(e.getKey())) continue;
                missing.append('\n');
                for (String line : e.getValue()[1].split("\n")) missing.append("# ").append(line).append('\n');
                missing.append(e.getKey()).append('=').append(e.getValue()[0]).append('\n');
                props.setProperty(e.getKey(), e.getValue()[0]);
            }
            if (!missing.isEmpty()) {
                try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    out.write(missing.toString());
                }
            }
        } catch (IOException e) {
            warn.accept("could not read or update " + file + " (" + e.getMessage() + "); using defaults");
        }

        String url = props.getProperty("url", "").trim();
        String sha1 = props.getProperty("sha1", "").trim();
        String secretRaw = props.getProperty("secret", "");
        for (String var : Secrets.missingVariables(secretRaw, env)) {
            warn.accept("secret refers to environment variable " + var + ", which is not set");
        }
        Set<String> acceptFrom = Arrays.stream(props.getProperty("accept-from", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new ProxySettings(
                new PackInfo(url.isEmpty() ? null : url, sha1.isEmpty() ? null : sha1),
                Boolean.parseBoolean(props.getProperty("required", "false").trim()),
                props.getProperty("prompt", ""),
                Secrets.resolve(secretRaw, env),
                secretRaw,
                Mode.parse(props.getProperty("mode"), warn),
                acceptFrom,
                Boolean.parseBoolean(props.getProperty("resend-on-update", "true").trim()));
    }

    /** Redacts the secret: records print every component by default. */
    @Override
    public String toString() {
        return "ProxySettings[staticPack=" + staticPack + ", required=" + required + ", prompt=" + prompt
                + ", secret=" + (secret != null || (secretRaw != null && !secretRaw.isEmpty()) ? "<set>" : "<none>") + ", mode=" + mode
                + ", acceptFrom=" + acceptFrom + ", resendOnUpdate=" + resendOnUpdate + "]";
    }

    public boolean hasSecret() {
        return secret != null;
    }
}
