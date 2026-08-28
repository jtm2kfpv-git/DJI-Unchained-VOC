package local.n3view.voc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import local.n3view.voc.protocol.H264AccessUnit;

public final class OriginalStreamRecorderModelTest {
    @Test
    public void originalContainerTogglesBetweenLosslessMp4AndRawH264() {
        assertEquals(
                OriginalStreamRecorder.Container.RAW_H264,
                OriginalStreamRecorder.Container.LOSSLESS_MP4.next());
        assertEquals(
                OriginalStreamRecorder.Container.LOSSLESS_MP4,
                OriginalStreamRecorder.Container.RAW_H264.next());
    }

    @Test
    public void normalizesThreeByteCodecStartCodeForMuxerFormat() {
        byte[] source = {0, 0, 1, 0x67, 0x64};

        assertArrayEquals(
                new byte[] {0, 0, 0, 1, 0x67, 0x64},
                OriginalStreamRecorder.fourByteStartCode(source));
    }

    @Test
    public void rawFirstPayloadAddsMissingParameterSets() {
        byte[] sps = {0, 0, 0, 1, 0x67};
        byte[] pps = {0, 0, 0, 1, 0x68};
        byte[] idr = {0, 0, 0, 1, 0x65, 0x01};

        byte[] payload = OriginalStreamRecorder.firstPayload(
                new H264AccessUnit(idr, true, sps, pps));

        assertTrue(OriginalStreamRecorder.containsNalType(payload, 7));
        assertTrue(OriginalStreamRecorder.containsNalType(payload, 8));
        assertTrue(OriginalStreamRecorder.containsNalType(payload, 5));
        assertEquals(sps.length + pps.length + idr.length, payload.length);
    }
}
