package local.djiunchained.voc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecordingCoordinatorTest {
    @Test
    public void onlyOneRecorderCanOwnThePipeline() {
        RecordingCoordinator coordinator = new RecordingCoordinator(snapshot -> { });

        assertTrue(coordinator.requestStart(RecordingCoordinator.Kind.ORIGINAL_STREAM_MP4));
        assertFalse(coordinator.requestStart(RecordingCoordinator.Kind.SHORTS_MP4));
        assertTrue(coordinator.markActive(RecordingCoordinator.Kind.ORIGINAL_STREAM_MP4));
        assertTrue(coordinator.snapshot().controlsLocked());
    }

    @Test
    public void stopAndCompletionUnlockControls() {
        RecordingCoordinator coordinator = new RecordingCoordinator(snapshot -> { });
        coordinator.requestStart(RecordingCoordinator.Kind.SHORTS_MP4);
        coordinator.markActive(RecordingCoordinator.Kind.SHORTS_MP4);

        assertTrue(coordinator.requestStop());
        assertEquals(RecordingCoordinator.State.STOPPING, coordinator.snapshot().state());
        assertTrue(coordinator.snapshot().controlsLocked());

        coordinator.complete("Saved");
        assertEquals(RecordingCoordinator.State.IDLE, coordinator.snapshot().state());
        assertFalse(coordinator.snapshot().controlsLocked());
    }

    @Test
    public void errorsAreExplicitlyRetryable() {
        RecordingCoordinator coordinator = new RecordingCoordinator(snapshot -> { });
        coordinator.requestStart(RecordingCoordinator.Kind.SHORTS_MP4);
        coordinator.fail("Encoder failed");

        assertTrue(coordinator.snapshot().retryable());
        assertFalse(coordinator.requestStart(RecordingCoordinator.Kind.SHORTS_MP4));

        coordinator.resetAfterError();
        assertEquals(RecordingCoordinator.State.IDLE, coordinator.snapshot().state());
        assertTrue(coordinator.requestStart(RecordingCoordinator.Kind.SHORTS_MP4));
    }
}
