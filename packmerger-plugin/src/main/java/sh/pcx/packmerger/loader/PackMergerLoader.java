package sh.pcx.packmerger.loader;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;

/**
 * Bootstrap loader for PackMerger.
 *
 * <p>The shipped jar only contains this class (plus the small generated
 * {@link RuntimeDependencies} manifest) and the main plugin code. At enable
 * time, the loader downloads the runtime dependencies — most importantly the
 * MinIO SDK and its OkHttp / Jackson / BouncyCastle transitives — from Maven
 * Central into {@code plugins/PackMerger/libraries/}, verifies each
 * against its SHA-256, loads them through an isolated {@link URLClassLoader},
 * and then reflectively invokes {@code PackMergerBootstrap}'s lifecycle
 * methods.</p>
 *
 * <p>The effect is a ~500 KB shipped jar instead of a ~13 MB shaded jar. Jars
 * are cached per-plugin: downloads happen once, then subsequent starts reuse
 * {@code plugins/PackMerger/libraries/} after verifying the cached file's
 * checksum still matches.</p>
 *
 * <p>Pattern ported from xInventories — identical structure so both plugins
 * have the same operational shape.</p>
 */
public class PackMergerLoader extends JavaPlugin {

    /** Fully qualified name of the real plugin entry class, loaded via the isolated classloader. */
    private static final String BOOTSTRAP_CLASS = "sh.pcx.packmerger.PackMergerBootstrap";

    /** Maven repositories tried in order until a download succeeds. */
    private static final String[] MAVEN_REPOS = {
            "https://repo1.maven.org/maven2",
            "https://repo.maven.apache.org/maven2"
    };

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** The resolved Bootstrap instance, held as Object since it lives in a different classloader. */
    private Object bootstrap;

    /** Classloader holding all downloaded dependency jars plus the plugin jar itself. */
    private URLClassLoader isolatedClassLoader;

    @Override
    public void onLoad() {
        try {
            loadBootstrap();
            if (bootstrap != null) {
                bootstrap.getClass()
                        .getMethod("onLoad", JavaPlugin.class)
                        .invoke(bootstrap, this);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load PackMerger", e);
            throw new RuntimeException("Failed to load PackMerger", e);
        }
    }

    @Override
    public void onEnable() {
        if (bootstrap == null) {
            getLogger().severe("Bootstrap not initialized; disabling PackMerger.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            bootstrap.getClass()
                    .getMethod("onEnable", JavaPlugin.class)
                    .invoke(bootstrap, this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable PackMerger", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (bootstrap != null) {
            try {
                bootstrap.getClass()
                        .getMethod("onDisable", JavaPlugin.class)
                        .invoke(bootstrap, this);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error during PackMerger shutdown", e);
            }
        }
        if (isolatedClassLoader != null) {
            try {
                isolatedClassLoader.close();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Failed to close isolated classloader", e);
            }
        }
    }

    /** Downloads + verifies every runtime dep, then constructs the bootstrap inside an isolated classloader. */
    private void loadBootstrap() throws Exception {
        Path librariesDir = getDataFolder().toPath().resolve("libraries");
        Files.createDirectories(librariesDir);

        getLogger().info("Resolving runtime dependencies (" + RuntimeDependencies.DEPENDENCIES.length + " artifacts)…");

        List<URL> jarUrls = new ArrayList<>();
        for (String[] dep : RuntimeDependencies.DEPENDENCIES) {
            Path jarPath = resolveDependency(librariesDir, dep[0], dep[1], dep[2], dep[3]);
            jarUrls.add(jarPath.toUri().toURL());
        }

        // Include the plugin jar itself so the bootstrap class + its package siblings
        // are loaded through the same isolated classloader as the dependencies.
        URL pluginJar = getClass().getProtectionDomain().getCodeSource().getLocation();
        jarUrls.add(pluginJar);

        isolatedClassLoader = new IsolatedURLClassLoader(
                jarUrls.toArray(new URL[0]),
                getClass().getClassLoader());

        Class<?> bootstrapClass = isolatedClassLoader.loadClass(BOOTSTRAP_CLASS);
        Constructor<?> constructor = bootstrapClass.getConstructor();
        bootstrap = constructor.newInstance();
    }

    /**
     * Downloads (or reuses a cached copy of) one dependency jar, verifying
     * SHA-256. Tries each Maven mirror in order; failures fall through.
     */
    private Path resolveDependency(Path librariesDir, String groupId, String artifactId,
                                   String version, String expectedSha256) throws Exception {
        String fileName = artifactId + "-" + version + ".jar";
        Path target = librariesDir.resolve(fileName);

        if (Files.exists(target)) {
            if (verifyChecksum(target, expectedSha256)) {
                return target;
            }
            getLogger().warning("Checksum mismatch for cached " + fileName + "; re-downloading");
            Files.delete(target);
        }

        String mavenPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + fileName;
        Exception lastException = null;
        for (String repo : MAVEN_REPOS) {
            String url = repo + "/" + mavenPath;
            try {
                getLogger().info("Downloading " + fileName + "…");
                downloadFile(url, target);
                if (verifyChecksum(target, expectedSha256)) {
                    return target;
                }
                getLogger().warning("Checksum verification failed for " + fileName + " from " + repo);
                Files.deleteIfExists(target);
            } catch (Exception e) {
                lastException = e;
                getLogger().fine("Failed to download from " + repo + ": " + e.getMessage());
            }
        }
        throw new RuntimeException("Failed to resolve " + fileName, lastException);
    }

    private void downloadFile(String urlString, Path target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "PackMerger-Loader/1.0");
        try {
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + conn.getResponseCode() + " " + conn.getResponseMessage());
            }
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Base64-encoded SHA-256 match — same format the build-time generator writes. */
    private boolean verifyChecksum(Path path, String expectedBase64) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            return Base64.getEncoder().encodeToString(hash).equals(expectedBase64);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to verify checksum for " + path.getFileName(), e);
            return false;
        }
    }

    /**
     * Public API types: always loaded by the server's plugin classloader (the parent),
     * never by the isolated one. Other plugins compile against these and resolve them
     * through PackMerger's plugin classloader; if the bootstrap used its own isolated
     * copies, {@code bootstrap instanceof PackMergerApi} would be false for them and the
     * events PackMerger fires would have a different {@code HandlerList} than the one
     * their listeners registered with, so they would never be delivered. Every type that
     * appears in an API signature must be listed here too.
     */
    static final String[] SHARED_API_PREFIXES = {
            "sh.pcx.packmerger.api.",
            "sh.pcx.packmerger.merge.MergeProvenance",          // + nested $FileRecord, $Builder
            "sh.pcx.packmerger.merge.PackValidator$ValidationResult"
    };

    /**
     * @return {@code true} if {@code className} is loaded from the isolated classpath
     *         (dependencies and PackMerger internals), {@code false} if it is delegated to
     *         the server's plugin classloader (the loader itself, the public API, Bukkit,
     *         the JDK and other plugins)
     */
    static boolean loadsFromIsolated(String className) {
        // Loader classes stay in the parent classloader — they're the entry point.
        if (className.startsWith("sh.pcx.packmerger.loader.")) return false;
        for (String prefix : SHARED_API_PREFIXES) {
            if (className.startsWith(prefix)) return false;
        }
        for (String prefix : IsolatedURLClassLoader.SELF_LOAD_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * @return PackMerger's API, for plugins that look PackMerger up by name. The usual way
     *         is {@code Bukkit.getServicesManager().load(PackMergerApi.class)} or
     *         {@link sh.pcx.packmerger.api.PackMergerApi#get()}.
     * @throws IllegalStateException if PackMerger failed to load
     */
    public sh.pcx.packmerger.api.PackMergerApi getApi() {
        if (bootstrap instanceof sh.pcx.packmerger.api.PackMergerApi api) return api;
        throw new IllegalStateException("PackMerger is not loaded");
    }

    /**
     * Loads plugin + dependency classes from the isolated classpath before
     * falling back to the parent classloader. Without this, the server's
     * classloader intercepts plugin classes before the isolated jar's
     * versions can be used, and dependency classes leak between plugins.
     */
    private static class IsolatedURLClassLoader extends URLClassLoader {

        /**
         * Prefixes of classes that should be loaded from the isolated jars
         * first. Everything else (Bukkit API, JDK, other plugins) falls
         * through to the parent classloader.
         */
        static final String[] SELF_LOAD_PREFIXES = {
                "io.minio.",
                "okhttp3.",
                "okio.",
                "kotlin.",
                "org.bouncycastle.",
                "com.fasterxml.jackson.",
                "com.google.common.",      // guava used by MinIO
                "com.carrotsearch.",
                "org.xerial.snappy.",
                "sh.pcx.packmerger."       // plugin classes (except loader)
        };

        IsolatedURLClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c != null) {
                    if (resolve) resolveClass(c);
                    return c;
                }
                if (shouldLoadFromSelf(name)) {
                    try {
                        c = findClass(name);
                        if (resolve) resolveClass(c);
                        return c;
                    } catch (ClassNotFoundException ignored) {
                        // fall through
                    }
                }
                return super.loadClass(name, resolve);
            }
        }

        private boolean shouldLoadFromSelf(String name) {
            return loadsFromIsolated(name);
        }
    }
}
