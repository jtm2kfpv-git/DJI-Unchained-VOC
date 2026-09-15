package local.djiunchained.voc;

import java.util.Objects;

/**
 * Single source of truth for recorder ownership. It prevents raw and MP4 recording from
 * overlapping and exposes whether display/aspect controls must be locked.
 */
public final class RecordingCoordinator {
    public enum Kind {
        NONE,
        ORIGINAL_STREAM_MP4,
        RAW_H264
    }

    public enum State {
        IDLE,
        STARTING,
        ACTIVE,
        STOPPING,
        ERROR
    }

    public record Snapshot(
            Kind kind,
            State state,
            boolean controlsLocked,
            boolean retryable,
            String message) {
    }

    public interface Listener {
        void onRecordingStateChanged(Snapshot snapshot);
    }

    private final Listener listener;
    private Kind kind = Kind.NONE;
    private State state = State.IDLE;
    private String message = "Not recording";

    public RecordingCoordinator(Listener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    public synchronized boolean requestStart(Kind requestedKind) {
        if (requestedKind == null || requestedKind == Kind.NONE || state != State.IDLE) {
            return false;
        }
        kind = requestedKind;
        state = State.STARTING;
        message = switch (requestedKind) {
            case ORIGINAL_STREAM_MP4 -> "Preparing lossless original-stream MP4";
            case RAW_H264 -> "Preparing raw original-stream H.264";
            case NONE -> throw new IllegalStateException("NONE cannot own the recorder");
        };
        notifyListener();
        return true;
    }

    public synchronized boolean markActive(Kind activeKind) {
        if (activeKind != kind || state != State.STARTING) {
            return false;
        }
        state = State.ACTIVE;
        message = switch (activeKind) {
            case ORIGINAL_STREAM_MP4 -> "Remuxing the original incoming stream into MP4";
            case RAW_H264 -> "Recording original incoming H.264 bytes";
            case NONE -> throw new IllegalStateException("NONE cannot be active");
        };
        notifyListener();
        return true;
    }

    public synchronized boolean requestStop() {
        if (state != State.STARTING && state != State.ACTIVE) {
            return false;
        }
        state = State.STOPPING;
        message = "Finishing recording safely";
        notifyListener();
        return true;
    }

    public synchronized void complete(String completionMessage) {
        kind = Kind.NONE;
        state = State.IDLE;
        message = completionMessage == null || completionMessage.isBlank()
                ? "Not recording" : completionMessage;
        notifyListener();
    }

    public synchronized void fail(String failureMessage) {
        state = State.ERROR;
        message = failureMessage == null || failureMessage.isBlank()
                ? "Recording failed" : failureMessage;
        notifyListener();
    }

    public synchronized void resetAfterError() {
        if (state == State.ERROR) {
            complete("Ready to retry recording");
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                kind,
                state,
                state == State.STARTING || state == State.ACTIVE || state == State.STOPPING,
                state == State.ERROR,
                message);
    }

    private void notifyListener() {
        listener.onRecordingStateChanged(snapshot());
    }
}
