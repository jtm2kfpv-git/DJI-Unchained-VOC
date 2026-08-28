package local.n3view.voc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MonotonicPresentationTimelineTest {
    @Test
    public void rebasesArrivalTimesAndPreservesCadence() {
        MonotonicPresentationTimeline timeline = new MonotonicPresentationTimeline();

        assertEquals(0, timeline.next(5_000_000));
        assertEquals(33_333, timeline.next(5_033_333));
        assertEquals(66_666, timeline.next(5_066_666));
        assertEquals(99_999, timeline.endPresentationUs());
        assertEquals(0, timeline.corrections());
        assertEquals(30.0, timeline.actualFramesPerSecond(), 0.001);
    }

    @Test
    public void repairsDuplicateAndBackwardTimestamps() {
        MonotonicPresentationTimeline timeline = new MonotonicPresentationTimeline();

        assertEquals(0, timeline.next(100));
        assertEquals(1, timeline.next(100));
        assertEquals(2, timeline.next(99));
        assertEquals(2, timeline.corrections());
    }
}
