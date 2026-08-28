package local.n3view.voc.protocol;

import java.util.Arrays;
import java.util.function.Consumer;

/** Incremental parser for the N3 mobile logiclink envelope. */
public final class LogicLinkDecoder {
    public static final int HEADER_SIZE = 8;
    public static final int VIDEO_PORT = 0x574A;
    public static final int CONTROL_IN_PORT = 0x7530;
    public static final int CONTROL_OUT_PORT = 0x5749;

    private byte[] buffer = new byte[256 * 1024];
    private int start;
    private int end;
    private long discardedBytes;

    public void accept(byte[] data, int offset, int length, Consumer<LogicLinkPacket> sink) {
        if (length < 0 || offset < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid input range");
        }
        ensureCapacity(length);
        System.arraycopy(data, offset, buffer, end, length);
        end += length;

        while (end - start >= HEADER_SIZE) {
            if ((buffer[start] & 0xFF) != 0x55 || (buffer[start + 1] & 0xFF) != 0xCC) {
                resynchronize();
                continue;
            }
            if (buffer[start + 6] != 0 || buffer[start + 7] != 0) {
                start++;
                discardedBytes++;
                continue;
            }

            int port = u16le(start + 2);
            int payloadLength = u16le(start + 4);
            int packetLength = HEADER_SIZE + payloadLength;
            if (end - start < packetLength) {
                break;
            }

            byte[] payload = Arrays.copyOfRange(buffer, start + HEADER_SIZE, start + packetLength);
            start += packetLength;
            sink.accept(new LogicLinkPacket(port, payload));
        }
        compactIfUseful();
    }

    public long discardedBytes() {
        return discardedBytes;
    }

    public void reset() {
        start = 0;
        end = 0;
        discardedBytes = 0;
    }

    private int u16le(int index) {
        return (buffer[index] & 0xFF) | ((buffer[index + 1] & 0xFF) << 8);
    }

    private void resynchronize() {
        int candidate = start + 1;
        while (candidate + 1 < end) {
            if ((buffer[candidate] & 0xFF) == 0x55 && (buffer[candidate + 1] & 0xFF) == 0xCC) {
                discardedBytes += candidate - start;
                start = candidate;
                return;
            }
            candidate++;
        }
        int keep = end > start && (buffer[end - 1] & 0xFF) == 0x55 ? 1 : 0;
        discardedBytes += (end - start) - keep;
        if (keep == 1) {
            buffer[0] = buffer[end - 1];
        }
        start = 0;
        end = keep;
    }

    private void ensureCapacity(int incoming) {
        int required = end + incoming;
        if (required <= buffer.length) {
            return;
        }
        compact();
        required = end + incoming;
        if (required > buffer.length) {
            int newLength = Math.max(required, buffer.length * 2);
            buffer = Arrays.copyOf(buffer, newLength);
        }
    }

    private void compactIfUseful() {
        if (start > buffer.length / 2 || (start == end && start != 0)) {
            compact();
        }
    }

    private void compact() {
        if (start == 0) {
            return;
        }
        int remaining = end - start;
        if (remaining > 0) {
            System.arraycopy(buffer, start, buffer, 0, remaining);
        }
        start = 0;
        end = remaining;
    }
}

