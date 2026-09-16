package local.djiunchained.voc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReplaySaveLifecycleTest {
    @Test
    public void rejectsConcurrentSaveAndCompletesWithTimelineMetrics() {
        ReplaySaveLifecycle lifecycle = new ReplaySaveLifecycle();
        assertTrue(lifecycle.begin(123, "Saving replay"));
        assertFalse(lifecycle.begin(456, "Second save"));
        lifecycle.sampleWritten(100);
        lifecycle.sampleWritten(200);
        MonotonicPresentationTimeline timeline = new MonotonicPresentationTimeline();
        timeline.next(1_000_000);
        timeline.next(1_033_333);

        ReplaySaveLifecycle.Snapshot snapshot = lifecycle.succeed(timeline);

        assertEquals(ReplaySaveLifecycle.State.IDLE, snapshot.state());
        assertEquals(300, snapshot.bytesWritten());
        assertEquals(2, snapshot.accessUnitsWritten());
        assertEquals(1, snapshot.completedSaves());
        assertEquals(33_333, snapshot.lastPresentationUs());
        assertEquals(30.0, snapshot.actualFramesPerSecond(), 0.001);
    }

    @Test
    public void failureCanBeRetriedAndResetsPerSaveCounters() {
        ReplaySaveLifecycle lifecycle = new ReplaySaveLifecycle();
        lifecycle.begin(1, "first");
        lifecycle.sampleWritten(50);
        ReplaySaveLifecycle.Snapshot failed = lifecycle.fail(
                new MonotonicPresentationTimeline(), new IllegalStateException("disk"));
        assertEquals(ReplaySaveLifecycle.State.ERROR, failed.state());
        assertTrue(failed.message().contains("disk"));

        assertTrue(lifecycle.begin(2, "retry"));
        ReplaySaveLifecycle.Snapshot retry = lifecycle.snapshot();
        assertEquals(ReplaySaveLifecycle.State.SAVING, retry.state());
        assertEquals(0, retry.bytesWritten());
        assertEquals(0, retry.accessUnitsWritten());
        assertEquals(0, retry.completedSaves());
    }
}
