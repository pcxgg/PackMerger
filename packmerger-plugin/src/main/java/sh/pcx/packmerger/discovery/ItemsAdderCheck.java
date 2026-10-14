package sh.pcx.packmerger.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Detects ItemsAdder settings that make its {@code generated.zip} unusable as a
 * merge input. Pure so it can be tested without a server; {@link PluginPackManager}
 * feeds it the values from {@code plugins/ItemsAdder/config.yml}.
 */
public final class ItemsAdderCheck {

    /** Config keys whose {@code true} value breaks merging, with the reason. */
    static final List<String[]> CONFLICTS = List.of(
            new String[]{"resource-pack.zip.protect-file-from-unzip.protection_1", "zip protection"},
            new String[]{"resource-pack.zip.protect-file-from-unzip.protection_2", "zip protection"},
            new String[]{"resource-pack.zip.protect-file-from-unzip.protection_3", "zip protection"},
            new String[]{"resource-pack.zip.compress-json-files", "JSON compression"});

    private ItemsAdderCheck() {}

    /**
     * @param isTrue tells whether a dotted ItemsAdder config key is set to {@code true}
     * @return the offending keys (empty when ItemsAdder's output can be merged)
     */
    public static List<String> conflicts(Predicate<String> isTrue) {
        List<String> out = new ArrayList<>();
        for (String[] c : CONFLICTS) if (isTrue.test(c[0])) out.add(c[0]);
        return out;
    }

    /** @return a one-line explanation for ops, or {@code null} when there is nothing to report */
    public static String advisory(List<String> conflicts) {
        if (conflicts.isEmpty()) return null;
        return "ItemsAdder has " + String.join(", ", conflicts) + " enabled; its generated.zip cannot be "
                + "merged reliably. Set them to false in plugins/ItemsAdder/config.yml and run /iazip";
    }
}
