package local.djiunchained.voc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class CaptureProfileTest {
    @Test
    public void shortsProfileIsPortraitAndKeepsSelectedFrameRate() {
        CaptureProfile profile = CaptureProfile.shorts(CaptureProfile.FrameRate.FPS_60, 0.25f);

        assertEquals(CaptureProfile.OutputFormat.SHORTS_9_16, profile.outputFormat());
        assertEquals(720, profile.width());
        assertEquals(1280, profile.height());
        assertEquals(60, profile.frameRate().framesPerSecond());
        assertEquals(0.25f, profile.horizontalCrop(), 0.0001f);
    }

    @Test
    public void frameRatesToggleBetweenThirtyAndSixty() {
        assertEquals(CaptureProfile.FrameRate.FPS_60, CaptureProfile.FrameRate.FPS_30.next());
        assertEquals(CaptureProfile.FrameRate.FPS_30, CaptureProfile.FrameRate.FPS_60.next());
    }

    @Test
    public void cropIsClampedAndLandscapeShortsIsRejected() {
        assertEquals(1f, CaptureProfile.clampCrop(4f), 0f);
        assertEquals(-1f, CaptureProfile.clampCrop(-4f), 0f);
        assertThrows(IllegalArgumentException.class, () -> new CaptureProfile(
                CaptureProfile.OutputFormat.SHORTS_9_16,
                1280,
                720,
                CaptureProfile.FrameRate.FPS_30,
                0f,
                1_000,
                1_000));
    }

    @Test
    public void eitherRecordingLimitStopsTheCapture() {
        CaptureProfile profile = CaptureProfile.shorts(CaptureProfile.FrameRate.FPS_30, 0f);

        assertFalse(profile.limitReached(1_000, 1_000));
        assertTrue(profile.limitReached(profile.maximumDurationMillis(), 1_000));
        assertTrue(profile.limitReached(1_000, profile.maximumBytes()));
    }
}
