package sh.pcx.packmerger.integration;

import sh.pcx.packmerger.discovery.ItemsAdderCheck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects item plugins (ItemsAdder, Oraxen, Nexo) that host and send their own resource
 * pack, or write it in a form PackMerger cannot read, and computes the config changes
 * that hand pack delivery to PackMerger. Pure: reads a {@link ConfigView} of the
 * plugin's config file, so it is unit tested without a server.
 *
 * <p>Keys and where they were verified (October 2026):</p>
 * <ul>
 *   <li><b>ItemsAdder</b> {@code config.yml}: {@code resource-pack.hosting.<method>.enabled}
 *       for {@code self-host}, {@code simple_self_host}, {@code external-host},
 *       {@code lobfile} and {@code no-host}; {@code resource-pack.zip.protect-file-from-unzip.*}
 *       and {@code resource-pack.zip.compress-json-files} (ItemsAdder wiki: hosting pages,
 *       "Resourcepack Protection", "Can't extract the resourcepack file").</li>
 *   <li><b>Oraxen</b> {@code settings.yml}: {@code Pack.upload.enabled},
 *       {@code Pack.dispatch.send} (Oraxen's bundled settings.yml on its master branch).
 *       {@code Pack.dispatch.send_pack} and {@code Pack.generation.protection} are older
 *       names that are not in the current file; they are only reported when present.</li>
 *   <li><b>Nexo</b> {@code settings.yml}: {@code Pack.server.type} ({@code NONE} = "does not
 *       send any ResourcePack to players", Nexo docs, ResourcePack page). Nexo's own
 *       default is not documented, so a missing key is not reported.</li>
 * </ul>
 * <p>Only keys that are present and explicitly enabled are reported: a missing key never
 * produces a warning.</p>
 */
public final class CompetingHosts {

    private CompetingHosts() {}

    /** Read access to a YAML config by dotted key. */
    public interface ConfigView {
        /** @return the value at {@code key}, or {@code null} if absent */
        Object get(String key);
    }

    /** Why a setting is a problem. */
    public enum Kind {
        /** The plugin hosts and/or sends its own pack: players get two packs. */
        HOSTING,
        /** The zip is deliberately malformed against unzipping: PackMerger can't read it. */
        ZIP_PROTECTION,
        /** Minified/compressed JSON that PackMerger can't merge reliably. */
        JSON_COMPRESSION
    }

    /**
     * A supported plugin.
     *
     * @param id         command argument ({@code /pm fixup <id>})
     * @param pluginName Bukkit plugin name
     * @param configFile config file inside the plugin's data folder
     * @param reloadHint what the operator runs afterwards to apply the change
     */
    public record Target(String id, String pluginName, String configFile, String reloadHint) {}

    public static final List<Target> TARGETS = List.of(
            new Target("itemsadder", "ItemsAdder", "config.yml", "/iareload, then /iazip"),
            new Target("oraxen", "Oraxen", "settings.yml", "/oraxen reload all (or restart the server)"),
            new Target("nexo", "Nexo", "settings.yml", "/nexo reload all (or restart the server)"));

    /**
     * One problematic setting.
     *
     * @param target  the plugin
     * @param kind    the problem
     * @param key     dotted config key
     * @param current its current value
     * @param fix     the value {@code /pm fixup} writes
     */
    public record Finding(Target target, Kind kind, String key, Object current, Object fix) {

        /** @return a stable id for {@code /pm fixup dismiss}: plugin, key and current value */
        public String id() {
            return target.id() + ":" + key + "=" + current;
        }

        /** @return one line: what is set, what is wrong, what to set instead */
        public String describe() {
            String why = switch (kind) {
                case HOSTING -> target.pluginName() + " hosts/sends its own pack, so players get two packs";
                case ZIP_PROTECTION -> "its zip is protected against unzipping, so PackMerger can't read it";
                case JSON_COMPRESSION -> "compressed JSON can't be merged reliably";
            };
            return target.pluginName() + " " + key + ": " + current + " (" + why + "; set it to " + fix + ")";
        }
    }

    /** @return the target with {@code id}, or {@code null} */
    public static Target target(String id) {
        for (Target t : TARGETS) if (t.id().equalsIgnoreCase(id)) return t;
        return null;
    }

    /** @return the problems in {@code cfg}, the config of {@code target} */
    public static List<Finding> detect(Target target, ConfigView cfg) {
        List<Finding> out = new ArrayList<>();
        switch (target.id()) {
            case "itemsadder" -> {
                for (String method : List.of("self-host", "simple_self_host", "external-host", "lobfile")) {
                    String key = "resource-pack.hosting." + method + ".enabled";
                    if (isTrue(cfg.get(key))) out.add(new Finding(target, Kind.HOSTING, key, true, false));
                }
                for (String[] c : ItemsAdderCheck.CONFLICTS) {
                    if (!isTrue(cfg.get(c[0]))) continue;
                    Kind kind = c[0].endsWith("compress-json-files") ? Kind.JSON_COMPRESSION : Kind.ZIP_PROTECTION;
                    out.add(new Finding(target, kind, c[0], true, false));
                }
            }
            case "oraxen" -> {
                for (String key : List.of("Pack.upload.enabled", "Pack.dispatch.send", "Pack.dispatch.send_pack")) {
                    if (isTrue(cfg.get(key))) out.add(new Finding(target, Kind.HOSTING, key, true, false));
                }
                if (isTrue(cfg.get("Pack.generation.protection"))) {
                    out.add(new Finding(target, Kind.ZIP_PROTECTION, "Pack.generation.protection", true, false));
                }
            }
            case "nexo" -> {
                Object type = cfg.get("Pack.server.type");
                if (type != null && !String.valueOf(type).isBlank()
                        && !String.valueOf(type).trim().equalsIgnoreCase("NONE")) {
                    out.add(new Finding(target, Kind.HOSTING, "Pack.server.type", type, "NONE"));
                }
            }
            default -> throw new IllegalArgumentException("unknown target " + target.id());
        }
        return out;
    }

    /**
     * @return the keys and values {@code /pm fixup} writes for these findings, in order.
     *         For ItemsAdder, turning off its hosting also switches on {@code no-host}
     *         (its documented "handle hosting yourself" mode).
     */
    public static Map<String, Object> fixes(List<Finding> findings) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean itemsAdderHosting = false;
        for (Finding f : findings) {
            out.put(f.key(), f.fix());
            if (f.kind() == Kind.HOSTING && f.target().id().equals("itemsadder")) itemsAdderHosting = true;
        }
        if (itemsAdderHosting) out.put("resource-pack.hosting.no-host.enabled", true);
        return out;
    }

    private static boolean isTrue(Object v) {
        if (v instanceof Boolean b) return b;
        return v != null && String.valueOf(v).trim().toLowerCase(Locale.ROOT).equals("true");
    }
}
