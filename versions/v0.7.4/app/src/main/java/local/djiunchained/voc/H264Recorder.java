package local.djiunchained.voc;

import local.djiunchained.voc.protocol.H264AccessUnit;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Non-blocking raw Annex-B recorder. Storage can never block the USB/video thread. */
final class H264Recorder implements Closeable {
    private static final int DEFAULT_QUEUE_CAPACITY = 120;

    enum State {
        IDLE,
        WAITING_FOR_KEYFRAME,
        RECORDING,
        STOPPING,
        ERROR
    }

    record Snapshot(
            State state,
            long bytesWritten,
            long accessUnitsWritten,
            long droppedUnits,
            long startedAtMillis,
            String message) {
        boolean active() {
            return state == State.WAITING_FOR_KEYFRAME
                    || state == State.RECORDING
                    || state == State.STOPPING;
        }
    }

    interface Listener {
        void onRecordingChanged(Snapshot snapshot);
    }

    private final Listener listener;
    private final LongSupplier clockMillis;
    private final ArrayBlockingQueue<byte[]> queue;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private State state = State.IDLE;
    private OutputStream output;
    private long generation;
    private long bytesWritten;
    private long accessUnitsWritten;
    private long droppedUnits;
    private long startedAtMillis;
    private String message = "Not recording";
    private boolean stopRequested;
    private boolean closed;
    private CountDownLatch settled = new CountDownLatch(0);

    H264Recorder(Listener listener) {
        this(listener, () -> System.nanoTime() / 1_000_000, DEFAULT_QUEUE_CAPACITY);
    }

    H264Recorder(Listener listener, LongSupplier clockMillis, int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.listener = listener;
        this.clockMillis = clockMillis;
        queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    boolean start(OutputStream destination) {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        Snapshot changed;
        long recordingGeneration;
        synchronized (this) {
            if (closed || output != null) {
                return false;
            }
            queue.clear();
            output = destination;
            generation++;
            recordingGeneration = generation;
            bytesWritten = 0;
            accessUnitsWritten = 0;
            droppedUnits = 0;
            startedAtMillis = 0;
            stopRequested = false;
            state = State.WAITING_FOR_KEYFRAME;
            message = "Waiting for SPS/PPS and an IDR keyframe";
            settled = new CountDownLatch(1);
            changed = snapshotLocked();
        }
        writer.execute(() -> writerLoop(recordingGeneration, destination));
        publish(changed);
        return true;
    }

    void accept(H264AccessUnit unit) {
        Snapshot changed = null;
        synchronized (this) {
            if (state == State.WAITING_FOR_KEYFRAME) {
                if (!unit.keyFrame() || unit.sps() == null || unit.pps() == null) {
                    return;
                }
                byte[] first = firstPayload(unit);
                if (!queue.offer(first)) {
                    changed = failForOverloadLocked();
                } else {
                    state = State.RECORDING;
                    startedAtMillis = clockMillis.getAsLong();
                    message = "Recording raw H.264";
                    changed = snapshotLocked();
                }
            } else if (state == State.RECORDING && !queue.offer(unit.data())) {
                changed = failForOverloadLocked();
            }
        }
        publish(changed);
    }

    void stop(String reason) {
        Snapshot changed = null;
        synchronized (this) {
            if (state == State.WAITING_FOR_KEYFRAME || state == State.RECORDING) {
                state = State.STOPPING;
                stopRequested = true;
                message = reason;
                changed = snapshotLocked();
            }
        }
        publish(changed);
    }

    synchronized Snapshot snapshot() {
        return snapshotLocked();
    }

    boolean awaitSettled(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch current;
        synchronized (this) {
            current = settled;
        }
        return current.await(timeout, unit);
    }

    private void writerLoop(long recordingGeneration, OutputStream destination) {
        IOException failure = null;
        try {
            while (true) {
                if (shouldFinish(recordingGeneration)) {
                    break;
                }
                byte[] data = queue.poll(100, TimeUnit.MILLISECONDS);
                if (data != null) {
                    destination.write(data);
                    synchronized (this) {
                        if (recordingGeneration != generation) {
                            break;
                        }
                        bytesWritten += data.length;
                        accessUnitsWritten++;
                    }
                }
            }
            destination.flush();
        } catch (IOException error) {
            failure = error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failure = new IOException("Recording writer interrupted", error);
        } finally {
            try {
                destination.close();
            } catch (IOException closeError) {
                if (failure == null) {
                    failure = closeError;
                }
            }
            finish(recordingGeneration, failure);
        }
    }

    private synchronized boolean shouldFinish(long recordingGeneration) {
        return recordingGeneration != generation
                || state == State.ERROR
                || (stopRequested && queue.isEmpty());
    }

    private void finish(long recordingGeneration, IOException failure) {
        Snapshot changed = null;
        boolean shutDown;
        synchronized (this) {
            if (recordingGeneration == generation) {
                output = null;
                queue.clear();
                if (failure != null) {
                    state = State.ERROR;
                    message = "Recording failed: " + concise(failure);
                } else if (state != State.ERROR) {
                    state = State.IDLE;
                    message = "Recording saved";
                }
                settled.countDown();
                changed = snapshotLocked();
            }
            shutDown = closed;
        }
        publish(changed);
        if (shutDown) {
            writer.shutdown();
        }
    }

    private Snapshot failForOverloadLocked() {
        droppedUnits++;
        queue.clear();
        stopRequested = true;
        state = State.ERROR;
        message = "Recording stopped: storage writer could not keep up";
        return snapshotLocked();
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                state, bytesWritten, accessUnitsWritten, droppedUnits, startedAtMillis, message);
    }

    private void publish(Snapshot changed) {
        if (changed != null && listener != null) {
            listener.onRecordingChanged(changed);
        }
    }

    private static byte[] firstPayload(H264AccessUnit unit) {
        boolean hasSps = containsNalType(unit.data(), 7);
        boolean hasPps = containsNalType(unit.data(), 8);
        if (hasSps && hasPps) {
            return unit.data();
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(
                unit.data().length + unit.sps().length + unit.pps().length);
        if (!hasSps) {
            result.write(unit.sps(), 0, unit.sps().length);
        }
        if (!hasPps) {
            result.write(unit.pps(), 0, unit.pps().length);
        }
        result.write(unit.data(), 0, unit.data().length);
        return result.toByteArray();
    }

    private static boolean containsNalType(byte[] data, int wantedType) {
        for (int i = 0; i + 3 < data.length; i++) {
            int prefixLength = 0;
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                prefixLength = 3;
            } else if (i + 4 < data.length && data[i] == 0 && data[i + 1] == 0
                    && data[i + 2] == 0 && data[i + 3] == 1) {
                prefixLength = 4;
            }
            if (prefixLength > 0 && i + prefixLength < data.length
                    && (data[i + prefixLength] & 0x1F) == wantedType) {
                return true;
            }
        }
        return false;
    }

    private static String concise(Throwable error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null ? "" : " - " + detail);
    }

    @Override
    public void close() {
        boolean shutDownNow;
        Snapshot changed = null;
        synchronized (this) {
            closed = true;
            if (state == State.WAITING_FOR_KEYFRAME || state == State.RECORDING) {
                state = State.STOPPING;
                stopRequested = true;
                message = "Application closing";
                changed = snapshotLocked();
            }
            shutDownNow = output == null;
        }
        publish(changed);
        if (shutDownNow) {
            writer.shutdown();
        }
    }
}
