package sh.pcx.packmerger.merge.strategy;

import java.util.Locale;

/**
 * How {@link ModelMergeStrategy} and {@link BlockstateMergeStrategy} combine two
 * packs' copies of the same model or blockstate file ({@code merge.model-policy}).
 */
public enum ModelPolicy {

    /**
     * The higher-priority file wins whole. Only the list-shaped extension points
     * are combined: a model's {@code overrides} and a blockstate's
     * {@code multipart} (when both files use multipart). This never produces a
     * hybrid asset, e.g. a {@code item/generated} model that inherits another
     * pack's {@code elements}. Default since config-version 4.
     */
    WHOLE_FILE,

    /**
     * The pre-config-version-4 behaviour: a recursive key-level deep merge, with
     * {@code overrides} / {@code multipart} concatenated. Kept for operators who
     * relied on two packs filling in different keys of the same model.
     */
    DEEP;

    /** Config key value, e.g. {@code whole-file}. */
    public String configValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Parses a config value ({@code whole-file} or {@code deep}, case-insensitive,
     * {@code _} accepted for {@code -}).
     *
     * @return the policy, or {@code null} if the value is not recognised
     */
    public static ModelPolicy parse(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ModelPolicy p : values()) {
            if (p.configValue().equals(v)) return p;
        }
        return null;
    }
}
