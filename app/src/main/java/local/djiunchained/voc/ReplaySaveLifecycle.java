package local.djiunchained.voc;

/** Thread-safe state and metrics model for asynchronous instant-replay saves. */
final class ReplaySaveLifecycle {
    enum State {
        IDLE,
        SAVING,
        ERROR
    }

    record Snapshot(
            State state,
            long bytesWritten,
            long accessUnitsWritten,
            long startedAtMillis,
            long firstPresentationUs,
            long lastPresentationUs,
            long timestampCorrections,
            double actualFramesPerSecond,
            long completedSaves,
            String message) {
        boolean active() {
            return state == State.SAVING;
        }
    }

    private State state = State.IDLE;
    private long bytesWritten;
    private long accessUnitsWritten;
    private long startedAtMillis;
    private long firstPresentationUs = -1;
    private long lastPresentationUs = -1;
    private long timestampCorrections;
    private double actualFramesPerSecond;
    private long completedSaves;
    private String message = "Replay saver idle";

    synchronized boolean begin(long startedAtMillis, String message) {
        if (state == State.SAVING) {
            return false;
        }
        state = State.SAVING;
        bytesWritten = 0;
        accessUnitsWritten = 0;
        this.startedAtMillis = startedAtMillis;
        firstPresentationUs = -1;
        lastPresentationUs = -1;
        timestampCorrections = 0;
        actualFramesPerSecond = 0;
        this.message = message;
        return true;
    }

    synchronized void sampleWritten(int bytes) {
        if (state != State.SAVING) {
            return;
        }
        bytesWritten += Math.max(0, bytes);
        accessUnitsWritten++;
    }

    synchronized Snapshot succeed(MonotonicPresentationTimeline timeline) {
        if (state != State.SAVING) {
            return snapshot();
        }
        captureTimeline(timeline);
        completedSaves++;
        state = State.IDLE;
        message = String.format(
                java.util.Locale.ROOT,
                "Instant replay saved: %.1f MB",
                bytesWritten / 1_000_000.0);
        return snapshot();
    }

    synchronized Snapshot fail(MonotonicPresentationTimeline timeline, Throwable failure) {
        if (timeline != null) {
            captureTimeline(timeline);
        }
        state = State.ERROR;
        message = "Replay save failed: " + concise(failure);
        return snapshot();
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                state,
                bytesWritten,
                accessUnitsWritten,
                startedAtMillis,
                firstPresentationUs,
                lastPresentationUs,
                timestampCorrections,
                actualFramesPerSecond,
                completedSaves,
                message);
    }

    private void captureTimeline(MonotonicPresentationTimeline timeline) {
        firstPresentationUs = timeline.firstPresentationUs();
        lastPresentationUs = timeline.lastPresentationUs();
        timestampCorrections = timeline.corrections();
        actualFramesPerSecond = timeline.actualFramesPerSecond();
    }

    private static String concise(Throwable error) {
        if (error == null) {
            return "unknown failure";
        }
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null ? "" : " - " + detail);
    }
}
