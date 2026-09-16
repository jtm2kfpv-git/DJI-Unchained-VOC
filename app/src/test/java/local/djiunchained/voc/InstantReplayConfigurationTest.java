package local.djiunchained.voc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class InstantReplayConfigurationTest {
    @Test
    public void invalidStoredDurationFallsBackToThirtySeconds() {
        assertEquals(30, InstantReplayConfiguration.normalizeDurationSeconds(0));
        assertEquals(30, InstantReplayConfiguration.normalizeDurationSeconds(45));
    }

    @Test
    public void durationChoicesCycleFifteenThirtySixty() {
        assertEquals(30, InstantReplayConfiguration.nextDurationSeconds(15));
        assertEquals(60, InstantReplayConfiguration.nextDurationSeconds(30));
        assertEquals(15, InstantReplayConfiguration.nextDurationSeconds(60));
    }
}
