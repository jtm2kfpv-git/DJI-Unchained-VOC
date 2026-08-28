package local.n3view.voc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StreamWatchdogTest {
    @Test
    public void stalledStreamEscalatesThroughAllRecoveryStages() {
        StreamWatchdog watchdog = new StreamWatchdog();
        watchdog.setActive(true, 0, 0, 0);

        assertAction(StreamWatchdog.Action.NONE,
                watchdog.evaluate(7_999, 0, 0, true, true));
        assertAction(StreamWatchdog.Action.RESEND_KEEPALIVE,
                watchdog.evaluate(8_000, 0, 0, true, true));
        assertAction(StreamWatchdog.Action.NONE,
                watchdog.evaluate(10_999, 0, 0, true, true));
        assertAction(StreamWatchdog.Action.RESET_DECODER,
                watchdog.evaluate(11_000, 0, 0, true, true));
        assertAction(StreamWatchdog.Action.NONE,
                watchdog.evaluate(14_999, 0, 0, true, true));
        StreamWatchdog.Evaluation reopened = watchdog.evaluate(15_000, 0, 0, true, true);
        assertAction(StreamWatchdog.Action.REOPEN_USB, reopened);
        assertEquals(3, reopened.recoveryActions());
    }

    @Test
    public void renderedFrameProgressCancelsEscalation() {
        StreamWatchdog watchdog = new StreamWatchdog();
        watchdog.setActive(true, 0, 0, 0);
        assertAction(StreamWatchdog.Action.RESEND_KEEPALIVE,
                watchdog.evaluate(8_000, 0, 0, true, true));

        assertAction(StreamWatchdog.Action.NONE,
                watchdog.evaluate(9_000, 1, 1, true, true));
        assertAction(StreamWatchdog.Action.NONE,
                watchdog.evaluate(11_999, 1, 1, true, true));
        assertAction(StreamWatchdog.Action.RESEND_KEEPALIVE,
                watchdog.evaluate(12_000, 1, 1, true, true));
    }

    @Test
    public void recoveryRequiresBothOptInAndDisplaySurface() {
        StreamWatchdog watchdog = new StreamWatchdog();
        watchdog.setActive(true, 0, 0, 0);

        assertAction(StreamWatchdog.Action.NONE,
                watchdog.evaluate(8_000, 0, 0, false, true));
        assertAction(StreamWatchdog.Action.NONE,
                watchdog.evaluate(9_000, 0, 0, true, false));
        assertAction(StreamWatchdog.Action.RESEND_KEEPALIVE,
                watchdog.evaluate(10_000, 0, 0, true, true));
    }

    @Test
    public void inactiveConnectionNeverRecovers() {
        StreamWatchdog watchdog = new StreamWatchdog();
        watchdog.setActive(true, 0, 0, 0);
        watchdog.setActive(false, 1_000, 0, 0);

        assertAction(StreamWatchdog.Action.NONE,
                watchdog.evaluate(30_000, 0, 0, true, true));
    }

    @Test
    public void decoderCounterResetDoesNotCancelUsbEscalation() {
        StreamWatchdog watchdog = new StreamWatchdog();
        watchdog.setActive(true, 0, 10, 10);
        assertAction(StreamWatchdog.Action.RESEND_KEEPALIVE,
                watchdog.evaluate(8_000, 10, 10, true, true));
        assertAction(StreamWatchdog.Action.RESET_DECODER,
                watchdog.evaluate(11_000, 10, 10, true, true));

        assertAction(StreamWatchdog.Action.NONE,
                watchdog.evaluate(12_000, 10, 0, true, true));
        assertAction(StreamWatchdog.Action.REOPEN_USB,
                watchdog.evaluate(15_000, 10, 0, true, true));
    }

    private static void assertAction(
            StreamWatchdog.Action expected,
            StreamWatchdog.Evaluation evaluation) {
        assertEquals(expected, evaluation.action());
    }
}
