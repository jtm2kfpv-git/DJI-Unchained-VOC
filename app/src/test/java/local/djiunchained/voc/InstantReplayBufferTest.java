package local.djiunchained.voc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import local.djiunchained.voc.protocol.H264AccessUnit;

public final class InstantReplayBufferTest {
    private static final byte[] SPS = {0, 0, 0, 1, 0x67};
    private static final byte[] PPS = {0, 0, 0, 1, 0x68};

    @Test
    public void snapshotStartsAtAUsableKeyframeAfterDurationTrim() {
        InstantReplayBuffer buffer = new InstantReplayBuffer(100, 1_000);
        buffer.offer(unit(1, true), 0);
        buffer.offer(unit(2, false), 50);
        buffer.offer(unit(3, true), 100);
        buffer.offer(unit(4, false), 150);

        InstantReplayBuffer.Snapshot snapshot = buffer.snapshot();

        assertTrue(snapshot.ready());
        assertEquals(2, snapshot.samples().size());
        assertArrayEquals(new byte[] {3}, snapshot.samples().get(0).data());
        assertEquals(50, snapshot.retainedDurationUs());
    }

    @Test
    public void byteLimitDropsDependentFramesUntilNextKeyframe() {
        InstantReplayBuffer buffer = new InstantReplayBuffer(1_000, 11);
        buffer.offer(unit(1, true), 0);
        buffer.offer(unit(2, false), 10);

        assertFalse(buffer.snapshot().ready());

        buffer.offer(unit(3, true), 20);

        InstantReplayBuffer.Snapshot snapshot = buffer.snapshot();
        assertTrue(snapshot.ready());
        assertEquals(1, snapshot.samples().size());
        assertArrayEquals(new byte[] {3}, snapshot.samples().get(0).data());
        assertTrue(snapshot.evictedUnits() >= 2);
    }

    @Test
    public void bufferedDataIsIndependentOfProducerAndConsumerArrays() {
        InstantReplayBuffer buffer = new InstantReplayBuffer(1_000, 1_000);
        byte[] payload = {7};
        buffer.offer(new H264AccessUnit(payload, true, SPS, PPS), 0);
        payload[0] = 9;

        byte[] exported = buffer.snapshot().samples().get(0).data();
        exported[0] = 8;

        assertArrayEquals(new byte[] {7}, buffer.snapshot().samples().get(0).data());
    }

    @Test
    public void clearResetsContentsAndEvictionCounters() {
        InstantReplayBuffer buffer = new InstantReplayBuffer(10, 1_000);
        buffer.offer(unit(1, true), 0);
        buffer.offer(unit(2, true), 20);
        assertTrue(buffer.snapshot().evictedUnits() > 0);

        buffer.clear();

        InstantReplayBuffer.Snapshot snapshot = buffer.snapshot();
        assertFalse(snapshot.ready());
        assertEquals(0, snapshot.evictedUnits());
        assertEquals(0, snapshot.evictedBytes());
    }

    private static H264AccessUnit unit(int value, boolean keyFrame) {
        return new H264AccessUnit(
                new byte[] {(byte) value},
                keyFrame,
                keyFrame ? SPS : null,
                keyFrame ? PPS : null);
    }
}
