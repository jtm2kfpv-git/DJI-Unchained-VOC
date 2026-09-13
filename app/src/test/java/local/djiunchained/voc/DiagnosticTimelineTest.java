package local.djiunchained.voc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class DiagnosticTimelineTest {
    @Test
    public void timelineIsBoundedAndKeepsNewestEvents() {
        AtomicLong clock = new AtomicLong(1_000);
        DiagnosticTimeline timeline = new DiagnosticTimeline(2, clock::get);
        timeline.add("app", "created");
        clock.addAndGet(100);
        timeline.add("usb", "connected");
        clock.addAndGet(100);
        timeline.add("video", "streaming");

        assertEquals(2, timeline.snapshot().size());
        assertEquals("connected", timeline.snapshot().get(0).message());
        assertEquals("streaming", timeline.snapshot().get(1).message());
    }

    @Test
    public void exportSanitizesEmbeddedNewlines() {
        DiagnosticTimeline timeline = new DiagnosticTimeline(2, () -> 10);
        timeline.add("status", "first\nsecond");

        String exported = timeline.export();
        assertTrue(exported.contains("first second"));
        assertFalse(exported.contains("first\nsecond"));
    }
}
