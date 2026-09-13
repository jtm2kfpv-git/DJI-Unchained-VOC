package local.djiunchained.voc.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

public class N3ControlPacketsTest {
    @Test
    public void packetsHaveExpectedEnvelopeAndLengths() {
        byte[][] packets = N3ControlPackets.copies();
        assertEquals(2, packets.length);
        assertEquals(55, packets[0].length);
        assertEquals(37, packets[1].length);
        assertEquals(
                "55cc49572d000000552d04f20228f3fe40009902020000d507000000000013000d0063616d6361705f636f6d6d6f6e00000000d093923a",
                hex(packets[0]));
        assertEquals(
                "55cc49571b000000551b0475023cf4fe400088170000230041505000000000000258a63418",
                hex(packets[1]));

        for (byte[] packet : packets) {
            assertEquals(0x55, packet[0] & 0xFF);
            assertEquals(0xCC, packet[1] & 0xFF);
            assertEquals(LogicLinkDecoder.CONTROL_OUT_PORT,
                    (packet[2] & 0xFF) | ((packet[3] & 0xFF) << 8));
            int payloadLength = (packet[4] & 0xFF) | ((packet[5] & 0xFF) << 8);
            assertEquals(packet.length - 10, payloadLength);
            assertEquals(0, packet[6]);
            assertEquals(0, packet[7]);
        }
    }

    @Test
    public void callersReceiveDefensiveCopies() {
        byte[][] first = N3ControlPackets.copies();
        byte[][] second = N3ControlPackets.copies();
        assertNotSame(first[0], second[0]);
        first[0][0] = 0;
        assertEquals(0x55, second[0][0] & 0xFF);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xFF));
        }
        return result.toString();
    }
}
