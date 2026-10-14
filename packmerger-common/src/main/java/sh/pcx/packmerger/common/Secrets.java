package sh.pcx.packmerger.common;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${NAME}} placeholders in secrets from environment variables, so a
 * shared secret doesn't have to sit in a config file.
 */
public final class Secrets {

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private Secrets() {}

    /** Resolves against {@link System#getenv(String)}. */
    public static String resolve(String raw) {
        return resolve(raw, System::getenv);
    }

    /**
     * Replaces every {@code ${NAME}} with {@code env.apply(NAME)} (an unset variable
     * becomes the empty string) and trims the result.
     *
     * @return the resolved secret, or {@code null} if it is blank (no secret)
     */
    public static String resolve(String raw, Function<String, String> env) {
        if (raw == null) return null;
        Matcher m = ENV_VAR.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = env.apply(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        String resolved = out.toString().trim();
        return resolved.isEmpty() ? null : resolved;
    }

    /** @return the {@code ${NAME}} variables {@code raw} refers to that are unset or blank */
    public static java.util.List<String> missingVariables(String raw, Function<String, String> env) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (raw == null) return missing;
        Matcher m = ENV_VAR.matcher(raw);
        while (m.find()) {
            String value = env.apply(m.group(1));
            if (value == null || value.isBlank()) missing.add(m.group(1));
        }
        return missing;
    }
}
