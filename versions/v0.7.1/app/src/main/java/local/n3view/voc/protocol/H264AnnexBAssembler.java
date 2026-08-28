package local.n3view.voc.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Splits a fragmented Annex-B byte stream into access units. It uses AUD NALs when
 * present and first_mb_in_slice when they are absent.
 */
public final class H264AnnexBAssembler {
    private byte[] pending = new byte[0];
    private final ByteArrayOutputStream accessUnit = new ByteArrayOutputStream(256 * 1024);
    private boolean hasVcl;
    private boolean keyFrame;
    private byte[] sps;
    private byte[] pps;

    public void accept(byte[] bytes, Consumer<H264AccessUnit> sink) {
        byte[] joined = new byte[pending.length + bytes.length];
        System.arraycopy(pending, 0, joined, 0, pending.length);
        System.arraycopy(bytes, 0, joined, pending.length, bytes.length);

        int first = findStartCode(joined, 0);
        if (first < 0) {
            pending = joined.length > 4
                    ? Arrays.copyOfRange(joined, joined.length - 4, joined.length)
                    : joined;
            return;
        }

        int current = first;
        while (true) {
            int next = findStartCode(joined, current + startCodeLength(joined, current));
            if (next < 0) {
                pending = Arrays.copyOfRange(joined, current, joined.length);
                return;
            }
            consumeNal(Arrays.copyOfRange(joined, current, next), sink);
            current = next;
        }
    }

    public void reset() {
        pending = new byte[0];
        accessUnit.reset();
        hasVcl = false;
        keyFrame = false;
        sps = null;
        pps = null;
    }

    private void consumeNal(byte[] nal, Consumer<H264AccessUnit> sink) {
        int prefix = startCodeLength(nal, 0);
        if (prefix == 0 || prefix >= nal.length) {
            return;
        }
        int type = nal[prefix] & 0x1F;

        if (type == 9) {
            emitIfComplete(sink);
        } else if (type == 7 || type == 8) {
            if (hasVcl) {
                emitIfComplete(sink);
            }
            if (type == 7) {
                sps = nal.clone();
            } else {
                pps = nal.clone();
            }
        } else if (type == 1 || type == 5) {
            int firstMb = firstMbInSlice(nal, prefix + 1);
            if (hasVcl && firstMb == 0) {
                emitIfComplete(sink);
            }
            hasVcl = true;
            keyFrame |= type == 5;
        }
        accessUnit.write(nal, 0, nal.length);
    }

    private void emitIfComplete(Consumer<H264AccessUnit> sink) {
        if (hasVcl && accessUnit.size() > 0) {
            sink.accept(new H264AccessUnit(
                    accessUnit.toByteArray(), keyFrame,
                    sps == null ? null : sps.clone(),
                    pps == null ? null : pps.clone()));
        }
        accessUnit.reset();
        hasVcl = false;
        keyFrame = false;
    }

    private static int firstMbInSlice(byte[] nal, int offset) {
        if (offset >= nal.length) {
            return -1;
        }
        byte[] rbsp = new byte[nal.length - offset];
        int out = 0;
        int zeros = 0;
        for (int i = offset; i < nal.length; i++) {
            int value = nal[i] & 0xFF;
            if (zeros >= 2 && value == 0x03) {
                zeros = 0;
                continue;
            }
            rbsp[out++] = nal[i];
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return readUnsignedExpGolomb(rbsp, out);
    }

    private static int readUnsignedExpGolomb(byte[] data, int length) {
        int bit = 0;
        int zeros = 0;
        while (bit < length * 8 && !bitAt(data, bit)) {
            zeros++;
            bit++;
            if (zeros > 30) {
                return -1;
            }
        }
        if (bit >= length * 8) {
            return -1;
        }
        bit++;
        int value = 1;
        for (int i = 0; i < zeros; i++, bit++) {
            if (bit >= length * 8) {
                return -1;
            }
            value = (value << 1) | (bitAt(data, bit) ? 1 : 0);
        }
        return value - 1;
    }

    private static boolean bitAt(byte[] data, int bit) {
        return ((data[bit / 8] >> (7 - (bit % 8))) & 1) != 0;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int index) {
        if (index + 2 < data.length && data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1) {
            return 3;
        }
        if (index + 3 < data.length && data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 0 && data[index + 3] == 1) {
            return 4;
        }
        return 0;
    }
}
