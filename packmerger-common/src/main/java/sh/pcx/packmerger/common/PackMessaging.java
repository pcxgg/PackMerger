package sh.pcx.packmerger.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/**
 * Wire format for the backend/proxy messages on {@link #CHANNEL}.
 *
 * <p>Both sides share this codec so the format can't drift. Layout (version 2,
 * big-endian, strings as {@link DataOutputStream#writeUTF}):</p>
 * <pre>
 *   0x00 0x00 'P' 'M'          magic (a 1.2 proxy reads it as an empty URL and ignores it)
 *   byte    version (2)
 *   byte    kind (1 PUBLISH, 2 OFFER)
 *   UTF     server
 *   long    timestamp
 *   UTF     url
 *   UTF     sha1 (hex, or empty)
 *   long[2] player UUID (0,0 = none)
 *   long[2] pack UUID   (0,0 = none)
 *   boolean required
 *   UTF     prompt
 *   short   signature length (0 = unsigned)
 *   bytes   HMAC-SHA256(secret, every byte before the length field)
 * </pre>
 *
 * <p>The 1.2 format (just {@code UTF url, UTF sha1}) is still decoded, as an unsigned
 * {@link Auth#LEGACY} PUBLISH, so a proxy without a secret keeps working with older
 * backends during an upgrade.</p>
 */
public final class PackMessaging {

    /** Namespaced plugin-messaging channel (lowercase, as Bukkit/Velocity/Bungee require). */
    public static final String CHANNEL = "packmerger:pack";

    /** Current wire version. */
    public static final int VERSION = 2;

    private static final byte[] MAGIC = {0, 0, 'P', 'M'};
    private static final String HMAC = "HmacSHA256";

    /** How a decoded message was authenticated. */
    public enum Auth {
        /** Signed, and the signature matches the local secret. */
        VALID,
        /** Signed, but the signature does not match the local secret. */
        INVALID,
        /** Signed, but there is no local secret to check it against. */
        NOT_CHECKED,
        /** Not signed. */
        UNSIGNED,
        /** The unsigned 1.2 format. */
        LEGACY;

        /**
         * The acceptance rule both sides use: with a secret configured only a valid
         * signature is accepted; without one everything but a bad signature is.
         */
        public boolean acceptable(boolean secretConfigured) {
            return secretConfigured ? this == VALID : this != INVALID;
        }

        /** Human-readable reason a message with this auth was rejected. */
        public String rejectionReason() {
            return switch (this) {
                case INVALID -> "bad signature (the secrets on the proxy and the backend differ)";
                case UNSIGNED -> "the message is not signed (set the same secret on the backend)";
                case LEGACY -> "the message uses the unsigned 1.2 format (update PackMerger on the backend)";
                case NOT_CHECKED -> "the message is signed but no secret is configured here";
                case VALID -> "accepted";
            };
        }
    }

    /** A decoded message and how it was authenticated. */
    public record Decoded(PackMessage message, Auth auth) {}

    private PackMessaging() {}

    /**
     * Serializes {@code message}, signed with {@code secret} when it is non-blank.
     */
    public static byte[] encode(PackMessage message, String secret) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(message.kind().wire);
            out.writeUTF(message.server());
            out.writeLong(message.timestamp());
            out.writeUTF(nullToEmpty(message.pack().url()));
            out.writeUTF(nullToEmpty(message.pack().sha1Hex()));
            writeUuid(out, message.player());
            writeUuid(out, message.packId());
            out.writeBoolean(message.required());
            out.writeUTF(message.prompt());
            out.flush();
            byte[] body = baos.toByteArray();
            if (secret == null || secret.isBlank()) {
                out.writeShort(0);
            } else {
                byte[] sig = sign(body, secret);
                out.writeShort(sig.length);
                out.write(sig);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never throws
        }
        return baos.toByteArray();
    }

    /**
     * Parses a channel payload and checks its signature against {@code secret}
     * (blank or {@code null} = no secret configured).
     *
     * @throws IllegalArgumentException if the payload is malformed
     */
    public static Decoded decode(byte[] bytes, String secret) {
        if (bytes == null) throw new IllegalArgumentException("empty message");
        if (bytes.length < MAGIC.length || !Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            return new Decoded(decodeLegacy(bytes), Auth.LEGACY);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            in.skipNBytes(MAGIC.length);
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported message version " + version
                        + " (update PackMerger so the proxy and backends use the same version)");
            }
            PackMessage.Kind kind = PackMessage.Kind.fromWire(in.readUnsignedByte());
            String server = in.readUTF();
            long timestamp = in.readLong();
            String url = in.readUTF();
            String sha1 = in.readUTF();
            UUID player = readUuid(in);
            UUID packId = readUuid(in);
            boolean required = in.readBoolean();
            String prompt = in.readUTF();
            int bodyLength = bytes.length - in.available();
            int sigLength = in.readUnsignedShort();
            byte[] sig = in.readNBytes(sigLength);
            if (sig.length != sigLength) throw new IllegalArgumentException("truncated signature");
            if (in.available() > 0) throw new IllegalArgumentException("trailing bytes after the signature");

            PackMessage message = new PackMessage(kind, server, timestamp,
                    new PackInfo(emptyToNull(url), emptyToNull(sha1)), player, packId, required, prompt);
            Auth auth;
            if (sigLength == 0) {
                auth = Auth.UNSIGNED;
            } else if (secret == null || secret.isBlank()) {
                auth = Auth.NOT_CHECKED;
            } else {
                byte[] expected = sign(Arrays.copyOf(bytes, bodyLength), secret);
                auth = MessageDigest.isEqual(expected, sig) ? Auth.VALID : Auth.INVALID;
            }
            return new Decoded(message, auth);
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated message", e);
        }
    }

    /** HMAC-SHA256 of {@code body} keyed with the UTF-8 bytes of {@code secret}. */
    public static byte[] sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e); // part of every JRE
        }
    }

    /** The 1.2 payload: {@code UTF url, UTF sha1}. Kept for tests and old backends. */
    static byte[] encodeLegacy(PackInfo info) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF(nullToEmpty(info.url()));
            out.writeUTF(nullToEmpty(info.sha1Hex()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    private static PackMessage decodeLegacy(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String url = in.readUTF();
            String hash = in.readUTF();
            return PackMessage.publish("", 0L, new PackInfo(emptyToNull(url), emptyToNull(hash)));
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed message", e);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id == null ? 0L : id.getMostSignificantBits());
        out.writeLong(id == null ? 0L : id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        long msb = in.readLong();
        long lsb = in.readLong();
        return msb == 0L && lsb == 0L ? null : new UUID(msb, lsb);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
