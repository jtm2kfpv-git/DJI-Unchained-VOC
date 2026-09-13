package local.djiunchained.voc.protocol;

/**
 * Captured, non-flight-control packets that subscribe/keep alive N3 mobile video.
 * The two final DUML CRC16 bytes are present in the working capture but are not
 * included in the outer logiclink length field. Preserve the bytes verbatim.
 */
public final class N3ControlPackets {
    private N3ControlPackets() {
    }

    private static final byte[][] START_AND_KEEPALIVE = new byte[][] {
            parseHex(
                    "55cc49572d000000" +
                    "552d04f20228f3fe400099" +
                    "02020000d507000000000013000d0063616d6361705f636f6d6d6f6e00000000d093" +
                    "923a"),
            parseHex(
                    "55cc49571b000000" +
                    "551b0475023cf4fe400088" +
                    "170000230041505000000000000258a6" +
                    "3418")
    };

    public static byte[][] copies() {
        byte[][] result = new byte[START_AND_KEEPALIVE.length][];
        for (int i = 0; i < START_AND_KEEPALIVE.length; i++) {
            result[i] = START_AND_KEEPALIVE[i].clone();
        }
        return result;
    }

    private static byte[] parseHex(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("Odd hex string length");
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hex string");
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
