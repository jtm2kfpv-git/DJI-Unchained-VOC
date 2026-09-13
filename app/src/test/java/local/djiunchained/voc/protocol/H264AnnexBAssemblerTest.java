package local.djiunchained.voc.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class H264AnnexBAssemblerTest {
    @Test
    public void assemblesFragmentedIdrAccessUnit() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        write(stream, nal(9, new byte[] {(byte) 0xF0}));
        write(stream, nal(7, new byte[] {0x42, 0x00, 0x1F}));
        write(stream, nal(8, new byte[] {(byte) 0xCE, 0x06}));
        write(stream, nal(5, new byte[] {(byte) 0x80, 0x11, 0x22}));
        write(stream, nal(9, new byte[] {(byte) 0xF0}));
        write(stream, nal(1, new byte[] {(byte) 0x80, 0x33}));

        byte[] bytes = stream.toByteArray();
        H264AnnexBAssembler assembler = new H264AnnexBAssembler();
        List<H264AccessUnit> units = new ArrayList<>();
        assembler.accept(slice(bytes, 0, 7), units::add);
        assembler.accept(slice(bytes, 7, 13), units::add);
        assembler.accept(slice(bytes, 13, bytes.length), units::add);

        assertEquals(1, units.size());
        assertTrue(units.get(0).keyFrame());
        assertTrue(units.get(0).sps().length > 4);
        assertTrue(units.get(0).pps().length > 4);
    }

    @Test
    public void splitsSlicesWithoutAccessUnitDelimiters() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        write(stream, nal(7, new byte[] {0x42, 0x00, 0x1F}));
        write(stream, nal(8, new byte[] {(byte) 0xCE, 0x06}));
        write(stream, nal(1, new byte[] {(byte) 0x80, 0x11}));
        write(stream, nal(1, new byte[] {(byte) 0x80, 0x22}));
        write(stream, nal(9, new byte[] {(byte) 0xF0}));
        write(stream, nal(1, new byte[] {(byte) 0x80, 0x33}));

        H264AnnexBAssembler assembler = new H264AnnexBAssembler();
        List<H264AccessUnit> units = new ArrayList<>();
        assembler.accept(stream.toByteArray(), units::add);

        assertEquals(2, units.size());
        assertTrue(units.get(0).sps().length > 4);
        assertTrue(units.get(0).pps().length > 4);
    }

    @Test
    public void acceptsEveryByteAsAnIndependentFragment() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        write(stream, nal(9, new byte[] {(byte) 0xF0}));
        write(stream, nal(7, new byte[] {0x64, 0x00, 0x28}));
        write(stream, nal(8, new byte[] {(byte) 0xEE, 0x3C}));
        write(stream, nal(5, new byte[] {(byte) 0x80, 0x55, 0x66}));
        write(stream, nal(9, new byte[] {(byte) 0xF0}));
        write(stream, nal(1, new byte[] {(byte) 0x80, 0x77}));

        H264AnnexBAssembler assembler = new H264AnnexBAssembler();
        List<H264AccessUnit> units = new ArrayList<>();
        byte[] bytes = stream.toByteArray();
        for (byte value : bytes) {
            assembler.accept(new byte[] {value}, units::add);
        }

        assertEquals(1, units.size());
        assertTrue(units.get(0).keyFrame());
        assertTrue(units.get(0).data().length > 20);
    }

    private static byte[] nal(int type, byte[] payload) {
        byte[] result = new byte[5 + payload.length];
        result[0] = 0;
        result[1] = 0;
        result[2] = 0;
        result[3] = 1;
        result[4] = (byte) (0x60 | type);
        System.arraycopy(payload, 0, result, 5, payload.length);
        return result;
    }

    private static void write(ByteArrayOutputStream stream, byte[] bytes) {
        stream.write(bytes, 0, bytes.length);
    }

    private static byte[] slice(byte[] source, int start, int end) {
        byte[] result = new byte[end - start];
        System.arraycopy(source, start, result, 0, result.length);
        return result;
    }
}
