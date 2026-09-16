package local.djiunchained.voc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecordingSplitPolicyTest {
    @Test
    public void disabledPolicyNeverSplits() {
        assertFalse(RecordingSplitPolicy.disabled()
                .shouldStartNextPart(0, Long.MAX_VALUE, true));
    }

    @Test
    public void waitsForKeyframeAfterDurationIsReached() {
        RecordingSplitPolicy policy = RecordingSplitPolicy.durationUs(100);

        assertFalse(policy.shouldStartNextPart(1_000, 1_099, true));
        assertFalse(policy.shouldStartNextPart(1_000, 1_100, false));
        assertTrue(policy.shouldStartNextPart(1_000, 1_100, true));
    }

    @Test
    public void reportsDelayWhileWaitingForAnIndependentPartBoundary() {
        RecordingSplitPolicy policy = RecordingSplitPolicy.durationUs(100);

        assertEquals(0, policy.overdueUs(1_000, 1_050));
        assertEquals(25, policy.overdueUs(1_000, 1_125));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveCustomDuration() {
        RecordingSplitPolicy.durationUs(0);
    }
}
