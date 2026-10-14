package sh.pcx.packmerger.common;

import java.util.HexFormat;

/** Hex helpers shared by the backend and proxy plugins. */
public final class Hex {

    private Hex() {}

    /** @return the bytes of an even-length hex string, or {@code null} if it isn't one */
    public static byte[] decodeOrNull(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() % 2 != 0) return null;
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** @return lowercase hex of {@code bytes} */
    public static String encode(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
