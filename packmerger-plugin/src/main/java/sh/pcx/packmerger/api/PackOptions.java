package sh.pcx.packmerger.api;

/**
 * Options for {@link PackMergerApi#registerPack}. Immutable; start from
 * {@link #defaults()} and chain the {@code with...} methods.
 *
 * <pre>{@code
 * api.registerPack(this, "myplugin", getDataFolder().toPath().resolve("pack"),
 *         PackOptions.defaults().withCluster(true));
 * }</pre>
 */
public final class PackOptions {

    private static final PackOptions DEFAULTS = new PackOptions(false, true, false);

    private final boolean cluster;
    private final boolean mergeNow;
    private final boolean allowHttp;

    private PackOptions(boolean cluster, boolean mergeNow, boolean allowHttp) {
        this.cluster = cluster;
        this.mergeNow = mergeNow;
        this.allowHttp = allowHttp;
    }

    /** @return cluster off, merge now on, plain http refused */
    public static PackOptions defaults() { return DEFAULTS; }

    /**
     * @return {@code true} if the source folder holds several packs that are unioned
     *         into one. A folder that is not a pack itself but contains packs is treated
     *         as a cluster even when this is {@code false}.
     */
    public boolean cluster() { return cluster; }

    /** @return {@code true} to schedule a merge after registering (default) */
    public boolean mergeNow() { return mergeNow; }

    /** @return {@code true} to allow a plain {@code http://} download URL */
    public boolean allowHttp() { return allowHttp; }

    public PackOptions withCluster(boolean cluster) { return new PackOptions(cluster, mergeNow, allowHttp); }

    public PackOptions withMergeNow(boolean mergeNow) { return new PackOptions(cluster, mergeNow, allowHttp); }

    public PackOptions withAllowHttp(boolean allowHttp) { return new PackOptions(cluster, mergeNow, allowHttp); }

    @Override
    public String toString() {
        return "PackOptions[cluster=" + cluster + ", mergeNow=" + mergeNow + ", allowHttp=" + allowHttp + "]";
    }
}
