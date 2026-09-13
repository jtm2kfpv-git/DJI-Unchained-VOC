package local.djiunchained.voc.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class LogicLinkDecoderTest {
    @Test
    public void parsesFragmentedAndAdjacentPackets() {
        byte[] video = packet(LogicLinkDecoder.VIDEO_PORT, new byte[] {1, 2, 3, 4});
        byte[] control = packet(LogicLinkDecoder.CONTROL_IN_PORT, new byte[] {9, 8});
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(video, 0, video.length);
        stream.write(control, 0, control.length);

        LogicLinkDecoder decoder = new LogicLinkDecoder();
        List<LogicLinkPacket> packets = new ArrayList<>();
        byte[] all = stream.toByteArray();
        decoder.accept(all, 0, 5, packets::add);
        decoder.accept(all, 5, 4, packets::add);
        decoder.accept(all, 9, all.length - 9, packets::add);

        assertEquals(2, packets.size());
        assertEquals(LogicLinkDecoder.VIDEO_PORT, packets.get(0).port());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, packets.get(0).payload());
        assertEquals(LogicLinkDecoder.CONTROL_IN_PORT, packets.get(1).port());
    }

    @Test
    public void resynchronizesAtMagicBoundary() {
        byte[] valid = packet(LogicLinkDecoder.VIDEO_PORT, new byte[] {7});
        byte[] input = new byte[valid.length + 3];
        input[0] = 1;
        input[1] = 2;
        input[2] = 3;
        System.arraycopy(valid, 0, input, 3, valid.length);

        LogicLinkDecoder decoder = new LogicLinkDecoder();
        List<LogicLinkPacket> packets = new ArrayList<>();
        decoder.accept(input, 0, input.length, packets::add);

        assertEquals(1, packets.size());
        assertEquals(3, decoder.discardedBytes());
    }

    @Test
    public void parsesManyPacketsAcrossEverySmallChunkSize() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        List<byte[]> expected = new ArrayList<>();
        Random random = new Random(0x4E334C56L);
        for (int i = 0; i < 100; i++) {
            byte[] payload = new byte[i % 31];
            random.nextBytes(payload);
            expected.add(payload);
            byte[] framed = packet(LogicLinkDecoder.VIDEO_PORT, payload);
            stream.write(framed, 0, framed.length);
        }

        byte[] bytes = stream.toByteArray();
        for (int chunkSize = 1; chunkSize <= 31; chunkSize++) {
            LogicLinkDecoder decoder = new LogicLinkDecoder();
            List<LogicLinkPacket> actual = new ArrayList<>();
            for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                int count = Math.min(chunkSize, bytes.length - offset);
                decoder.accept(bytes, offset, count, actual::add);
            }
            assertEquals("chunk size " + chunkSize, expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) {
                assertArrayEquals("packet " + i + ", chunk size " + chunkSize,
                        expected.get(i), actual.get(i).payload());
            }
            assertEquals(0, decoder.discardedBytes());
        }
    }

    private static byte[] packet(int port, byte[] payload) {
        byte[] result = new byte[8 + payload.length];
        result[0] = 0x55;
        result[1] = (byte) 0xCC;
        result[2] = (byte) port;
        result[3] = (byte) (port >>> 8);
        result[4] = (byte) payload.length;
        result[5] = (byte) (payload.length >>> 8);
        System.arraycopy(payload, 0, result, 8, payload.length);
        return result;
    }
}
