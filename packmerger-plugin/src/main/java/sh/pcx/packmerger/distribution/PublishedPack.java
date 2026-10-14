package sh.pcx.packmerger.distribution;

import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * The pack PackMerger is currently offering to players: one immutable
 * {@code (url, sha1, uuid)} triple.
 *
 * <p>A record is only built after the upload provider has accepted the file, and it
 * is swapped in as a whole, so a player can never be sent a new hash with an old
 * URL (or the reverse). If an upload fails, the previous record simply stays live.</p>
 *
 * @param url               the public download URL returned by the upload provider
 * @param sha1              raw SHA-1 of the zip (20 bytes); defensively copied
 * @param packId            the stable pack UUID for this server (see {@link PackIdentity})
 * @param publishedAt       when the record went live
 * @param providerGeneration which upload-provider instance produced the URL; used to
 *                          detect that a re-initialised provider still needs the
 *                          existing pack re-published
 */
public record PublishedPack(String url, byte[] sha1, UUID packId, Instant publishedAt, int providerGeneration) {

    public PublishedPack {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (sha1 == null || sha1.length != 20) {
            throw new IllegalArgumentException("sha1 must be 20 bytes");
        }
        sha1 = sha1.clone();
    }

    /** @return a copy of the raw SHA-1 bytes */
    @Override
    public byte[] sha1() {
        return sha1.clone();
    }

    /** @return lowercase hex SHA-1 (40 characters) */
    public String sha1Hex() {
        return HexFormat.of().formatHex(sha1);
    }

    /** @return {@code true} if {@code other} carries the same pack bytes (same SHA-1) */
    public boolean sameContentAs(PublishedPack other) {
        return other != null && Arrays.equals(sha1, other.sha1);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PublishedPack p && url.equals(p.url) && Arrays.equals(sha1, p.sha1)
                && packId.equals(p.packId) && publishedAt.equals(p.publishedAt)
                && providerGeneration == p.providerGeneration;
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, Arrays.hashCode(sha1), packId, publishedAt, providerGeneration);
    }

    @Override
    public String toString() {
        return "PublishedPack[url=" + url + ", sha1=" + sha1Hex() + ", packId=" + packId + "]";
    }
}
