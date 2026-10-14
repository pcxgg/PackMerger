package sh.pcx.packmerger.upload;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Publishes through {@code upload.provider}, then through the providers named in
 * {@code upload.fallback} (in order) when the previous one could not be used.
 *
 * <p>A provider is skipped when it is not configured, when its upload throws, or when
 * its post-upload check (the self-host reachability verification) says players cannot
 * download from it. A provider whose check failed is still remembered: if every later
 * provider fails too, its URL is used anyway (a URL that may work for some players beats
 * no pack at all) and the outcome is marked degraded. Pure orchestration; the providers
 * are looked up through a function so this is unit-testable.</p>
 */
public final class UploadChain {

    private UploadChain() {}

    /** One provider as seen by the chain. */
    public interface Step {
        /** Uploads and returns the download URL; throws on failure. */
        String upload(File file, String sha1Hex) throws Exception;

        /**
         * Checks the URL that {@link #upload} returned.
         *
         * @return {@code null} if players can use it, otherwise the reason they cannot
         */
        default String check(String url) { return null; }

        /**
         * Called once this step's URL has been chosen, right before it is published
         * (self-host starts serving the uploaded pack at its public path only now).
         */
        default void commit() throws Exception {}
    }

    /**
     * @param url       the URL to publish
     * @param provider  the provider that produced it
     * @param fallback  {@code true} if it is not {@code upload.provider}
     * @param degraded  {@code true} if its check failed but nothing better was available
     * @param notes     why earlier providers were skipped (empty when the first one worked)
     */
    public record Outcome(String url, String provider, boolean fallback, boolean degraded, List<String> notes) {

        /** @return a one-line description of the route for /pm status and logs */
        public String describe() {
            StringBuilder sb = new StringBuilder(provider);
            if (fallback && degraded) sb.append(" (fallback; not verified, nothing better available)");
            else if (fallback) sb.append(" (fallback)");
            else if (degraded) sb.append(" (not verified, nothing better available)");
            if (!notes.isEmpty()) sb.append(": ").append(String.join("; ", notes));
            return sb.toString();
        }
    }

    /** @return {@code primary} followed by the fallback names, lower-cased and de-duplicated */
    public static List<String> order(String primary, List<String> fallbacks) {
        Set<String> names = new LinkedHashSet<>();
        names.add(primary.toLowerCase(Locale.ROOT).trim());
        for (String f : fallbacks) {
            if (f != null && !f.isBlank()) names.add(f.toLowerCase(Locale.ROOT).trim());
        }
        return List.copyOf(names);
    }

    /**
     * Runs the chain.
     *
     * @param names  provider names, primary first (see {@link #order})
     * @param lookup returns the step for a name, or {@code null} if that provider is not configured
     * @throws Exception the first provider's upload error if nothing could be published
     */
    public static Outcome run(List<String> names, Function<String, Step> lookup, File file, String sha1Hex)
            throws Exception {
        List<String> notes = new ArrayList<>();
        Exception firstError = null;
        Outcome degraded = null;
        Step degradedStep = null;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Step step = lookup.apply(name);
            if (step == null) {
                notes.add(name + " is not configured");
                continue;
            }
            String url;
            try {
                url = step.upload(file, sha1Hex);
            } catch (Exception e) {
                if (firstError == null) firstError = e;
                notes.add(name + " upload failed: " + e.getMessage());
                continue;
            }
            String problem = step.check(url);
            if (problem == null) {
                step.commit();
                return new Outcome(url, name, i > 0, false, List.copyOf(notes));
            }
            notes.add(name + ": " + problem);
            if (degraded == null) {
                degraded = new Outcome(url, name, i > 0, true, List.of());
                degradedStep = step;
            }
        }
        if (degraded != null) {
            degradedStep.commit();
            return new Outcome(degraded.url(), degraded.provider(), degraded.fallback(), true, List.copyOf(notes));
        }
        if (firstError != null) throw firstError;
        throw new IllegalStateException("no upload provider could be used: " + String.join("; ", notes));
    }
}
