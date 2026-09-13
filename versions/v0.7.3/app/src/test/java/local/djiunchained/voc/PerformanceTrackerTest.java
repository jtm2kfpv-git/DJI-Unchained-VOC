package local.djiunchained.voc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PerformanceTrackerTest {
    @Test
    public void calculatesRollingBitrateAndFrameRates() {
        PerformanceTracker tracker = new PerformanceTracker();
        tracker.addSample(0, 0, 0);
        tracker.addSample(1_000, 2_000_000, 50);
        tracker.addSample(2_000, 4_000_000, 100);

        PerformanceTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(16.0, snapshot.currentMegabitsPerSecond(), 0.001);
        assertEquals(16.0, snapshot.averageMegabitsPerSecond(), 0.001);
        assertEquals(50.0, snapshot.fps1Second(), 0.001);
        assertEquals(50.0, snapshot.fps5Seconds(), 0.001);
    }

    @Test
    public void recordsTheLongestRenderedFrameGap() {
        PerformanceTracker tracker = new PerformanceTracker();
        tracker.onFrame(100);
        tracker.onFrame(120);
        tracker.onFrame(190);

        assertEquals(70, tracker.snapshot().maximumFrameGapMillis());
    }

    @Test
    public void discardsSamplesOlderThanTheRollingWindow() {
        PerformanceTracker tracker = new PerformanceTracker();
        tracker.addSample(0, 0, 0);
        tracker.addSample(1_000, 1, 1);
        tracker.addSample(40_000, 2, 2);

        assertTrue(tracker.snapshot().sampleCount() <= 2);
    }
}
