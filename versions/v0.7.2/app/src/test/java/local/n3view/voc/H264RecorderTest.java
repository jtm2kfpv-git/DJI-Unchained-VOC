package local.n3view.voc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import local.n3view.voc.protocol.H264AccessUnit;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class H264RecorderTest {
    private static final byte[] SPS = {0, 0, 0, 1, 0x67, 0x11};
    private static final byte[] PPS = {0, 0, 0, 1, 0x68, 0x22};
    private static final byte[] IDR = {0, 0, 0, 1, 0x65, 0x33};
    private static final byte[] P_FRAME = {0, 0, 0, 1, 0x41, 0x44};

    @Test
    public void waitsForKeyframeAndPrependsMissingParameters() throws Exception {
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        H264Recorder recorder = new H264Recorder(null, () -> 1234, 4);
        assertTrue(recorder.start(destination));

        recorder.accept(new H264AccessUnit(P_FRAME, false, SPS, PPS));
        assertEquals(H264Recorder.State.WAITING_FOR_KEYFRAME, recorder.snapshot().state());
        recorder.accept(new H264AccessUnit(IDR, true, SPS, PPS));
        recorder.stop("test complete");

        assertTrue(recorder.awaitSettled(2, TimeUnit.SECONDS));
        assertArrayEquals(concat(SPS, PPS, IDR), destination.toByteArray());
        assertEquals(1, recorder.snapshot().accessUnitsWritten());
        assertEquals(1234, recorder.snapshot().startedAtMillis());
        recorder.close();
    }

    @Test
    public void doesNotDuplicateParametersAlreadyInFirstAccessUnit() throws Exception {
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        H264Recorder recorder = new H264Recorder(null, () -> 0, 4);
        byte[] completeKeyframe = concat(SPS, PPS, IDR);
        recorder.start(destination);
        recorder.accept(new H264AccessUnit(completeKeyframe, true, SPS, PPS));
        recorder.stop("test complete");

        assertTrue(recorder.awaitSettled(2, TimeUnit.SECONDS));
        assertArrayEquals(completeKeyframe, destination.toByteArray());
        recorder.close();
    }

    @Test
    public void writeFailureStopsRecordingWithoutThrowingToProducer() throws Exception {
        H264Recorder recorder = new H264Recorder(null, () -> 0, 2);
        recorder.start(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("test storage failure");
            }
        });

        recorder.accept(new H264AccessUnit(IDR, true, SPS, PPS));

        assertTrue(recorder.awaitSettled(2, TimeUnit.SECONDS));
        assertEquals(H264Recorder.State.ERROR, recorder.snapshot().state());
        assertTrue(recorder.snapshot().message().contains("test storage failure"));
        recorder.close();
    }

    @Test
    public void queueOverloadStopsOnlyTheRecorder() throws Exception {
        BlockingOutputStream destination = new BlockingOutputStream();
        H264Recorder recorder = new H264Recorder(null, () -> 0, 1);
        recorder.start(destination);
        recorder.accept(new H264AccessUnit(IDR, true, SPS, PPS));
        assertTrue(destination.writeStarted.await(2, TimeUnit.SECONDS));

        recorder.accept(new H264AccessUnit(P_FRAME, false, SPS, PPS));
        recorder.accept(new H264AccessUnit(P_FRAME, false, SPS, PPS));
        assertEquals(H264Recorder.State.ERROR, recorder.snapshot().state());
        assertEquals(1, recorder.snapshot().droppedUnits());

        destination.allowWrite.countDown();
        assertTrue(recorder.awaitSettled(2, TimeUnit.SECONDS));
        assertEquals(H264Recorder.State.ERROR, recorder.snapshot().state());
        recorder.close();
    }

    @Test
    public void stopBeforeKeyframeCreatesAnEmptyFile() throws Exception {
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        H264Recorder recorder = new H264Recorder(null, () -> 0, 2);
        recorder.start(destination);

        recorder.stop("cancelled while waiting");

        assertTrue(recorder.awaitSettled(2, TimeUnit.SECONDS));
        assertEquals(0, destination.size());
        assertEquals(H264Recorder.State.IDLE, recorder.snapshot().state());
        recorder.close();
    }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] value : values) {
            result.write(value, 0, value.length);
        }
        return result.toByteArray();
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch allowWrite = new CountDownLatch(1);

        @Override
        public void write(int value) throws IOException {
            // The recorder calls the byte-array overload below.
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            writeStarted.countDown();
            try {
                if (!allowWrite.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("test timed out waiting to release writer");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
        }
    }
}
