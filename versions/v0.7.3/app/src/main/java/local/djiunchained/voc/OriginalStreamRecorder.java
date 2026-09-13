package local.djiunchained.voc;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import local.djiunchained.voc.protocol.H264AccessUnit;

/**
 * Non-blocking recorder for the original incoming H.264 access units.
 *
 * <p>MP4 mode only remuxes the compressed stream and supplies monotonic timestamps; it does
 * not decode, filter or re-encode the video. Raw mode preserves the Annex-B bytes exactly.</p>
 */
final class OriginalStreamRecorder implements Closeable {
    private static final int DEFAULT_QUEUE_CAPACITY = 120;

    enum Container {
        LOSSLESS_MP4("Lossless MP4", "video/mp4"),
        RAW_H264("Raw H.264", "video/avc");

        private final String label;
        private final String mimeType;

        Container(String label, String mimeType) {
            this.label = label;
            this.mimeType = mimeType;
        }

        String label() {
            return label;
        }

        String mimeType() {
            return mimeType;
        }

        Container next() {
            return this == LOSSLESS_MP4 ? RAW_H264 : LOSSLESS_MP4;
        }
    }

    enum State {
        IDLE,
        WAITING_FOR_KEYFRAME,
        RECORDING,
        STOPPING,
        ERROR
    }

    record Snapshot(
            State state,
            Container container,
            long bytesWritten,
            long accessUnitsWritten,
            long droppedUnits,
            long startedAtMillis,
            long firstPresentationUs,
            long lastPresentationUs,
            long timestampCorrections,
            double actualFramesPerSecond,
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

    private record QueuedSample(
            byte[] data,
            boolean keyFrame,
            byte[] sps,
            byte[] pps,
            long arrivalUs) {
    }

    private final ContentResolver resolver;
    private final Listener listener;
    private final LongSupplier clockMillis;
    private final LongSupplier clockMicros;
    private final ArrayBlockingQueue<QueuedSample> queue;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final MonotonicPresentationTimeline presentationTimeline =
            new MonotonicPresentationTimeline();

    private State state = State.IDLE;
    private Container container = Container.LOSSLESS_MP4;
    private Uri destination;
    private int width;
    private int height;
    private long generation;
    private long bytesWritten;
    private long accessUnitsWritten;
    private long droppedUnits;
    private long startedAtMillis;
    private String message = "Not recording";
    private boolean stopRequested;
    private boolean closed;
    private CountDownLatch settled = new CountDownLatch(0);

    OriginalStreamRecorder(ContentResolver resolver, Listener listener) {
        this(resolver, listener,
                () -> System.nanoTime() / 1_000_000,
                () -> System.nanoTime() / 1_000,
                DEFAULT_QUEUE_CAPACITY);
    }

    OriginalStreamRecorder(
            ContentResolver resolver,
            Listener listener,
            LongSupplier clockMillis,
            LongSupplier clockMicros,
            int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.resolver = Objects.requireNonNull(resolver);
        this.listener = listener;
        this.clockMillis = Objects.requireNonNull(clockMillis);
        this.clockMicros = Objects.requireNonNull(clockMicros);
        queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    boolean start(Uri destination, Container container, int width, int height) {
        if (destination == null || container == null) {
            throw new IllegalArgumentException("Destination and container are required");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Video dimensions must be positive");
        }
        Snapshot changed;
        long recordingGeneration;
        synchronized (this) {
            if (closed || this.destination != null) {
                return false;
            }
            queue.clear();
            this.destination = destination;
            this.container = container;
            this.width = width;
            this.height = height;
            generation++;
            recordingGeneration = generation;
            bytesWritten = 0;
            accessUnitsWritten = 0;
            droppedUnits = 0;
            presentationTimeline.reset();
            startedAtMillis = 0;
            stopRequested = false;
            state = State.WAITING_FOR_KEYFRAME;
            message = "Waiting for SPS/PPS and an IDR keyframe";
            settled = new CountDownLatch(1);
            changed = snapshotLocked();
        }
        writer.execute(() -> writerLoop(recordingGeneration, destination, container, width, height));
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
                QueuedSample first = sample(unit, true);
                if (!queue.offer(first)) {
                    changed = failForOverloadLocked();
                } else {
                    state = State.RECORDING;
                    startedAtMillis = clockMillis.getAsLong();
                    message = container == Container.LOSSLESS_MP4
                            ? "Recording original stream as lossless MP4"
                            : "Recording original raw H.264";
                    changed = snapshotLocked();
                }
            } else if (state == State.RECORDING && !queue.offer(sample(unit, false))) {
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

    private QueuedSample sample(H264AccessUnit unit, boolean first) {
        byte[] payload = container == Container.RAW_H264 && first
                ? firstPayload(unit) : unit.data();
        return new QueuedSample(
                payload,
                unit.keyFrame(),
                unit.sps() == null ? null : unit.sps().clone(),
                unit.pps() == null ? null : unit.pps().clone(),
                clockMicros.getAsLong());
    }

    private void writerLoop(
            long recordingGeneration,
            Uri recordingDestination,
            Container recordingContainer,
            int recordingWidth,
            int recordingHeight) {
        Throwable failure = null;
        ParcelFileDescriptor descriptor = null;
        OutputStream rawOutput = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        int trackIndex = -1;
        try {
            descriptor = resolver.openFileDescriptor(recordingDestination, "rwt");
            if (descriptor == null) {
                throw new IOException("Recording destination could not be opened");
            }
            if (recordingContainer == Container.RAW_H264) {
                rawOutput = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
                descriptor = null;
            }

            while (true) {
                if (shouldFinish(recordingGeneration)) {
                    break;
                }
                QueuedSample sample = queue.poll(100, TimeUnit.MILLISECONDS);
                if (sample == null) {
                    continue;
                }
                long presentationUs = presentationTimeline.next(sample.arrivalUs());
                if (recordingContainer == Container.RAW_H264) {
                    rawOutput.write(sample.data());
                } else {
                    if (!muxerStarted) {
                        muxer = new MediaMuxer(
                                descriptor.getFileDescriptor(),
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                        MediaFormat format = MediaFormat.createVideoFormat(
                                MediaFormat.MIMETYPE_VIDEO_AVC,
                                recordingWidth,
                                recordingHeight);
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(fourByteStartCode(sample.sps())));
                        format.setByteBuffer("csd-1", ByteBuffer.wrap(fourByteStartCode(sample.pps())));
                        trackIndex = muxer.addTrack(format);
                        muxer.start();
                        muxerStarted = true;
                    }
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    info.set(0, sample.data().length, presentationUs,
                            sample.keyFrame() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    muxer.writeSampleData(trackIndex, ByteBuffer.wrap(sample.data()), info);
                }
                synchronized (this) {
                    if (recordingGeneration != generation) {
                        break;
                    }
                    bytesWritten += sample.data().length;
                    accessUnitsWritten++;
                }
            }

            if (rawOutput != null) {
                rawOutput.flush();
            }
            if (muxerStarted) {
                MediaCodec.BufferInfo end = new MediaCodec.BufferInfo();
                end.set(0, 0, presentationTimeline.endPresentationUs(),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                muxer.writeSampleData(trackIndex, ByteBuffer.allocate(0), end);
                muxer.stop();
            }
        } catch (IOException | RuntimeException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            failure = error;
        } finally {
            if (rawOutput != null) {
                try {
                    rawOutput.close();
                } catch (IOException closeError) {
                    if (failure == null) {
                        failure = closeError;
                    }
                }
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (RuntimeException releaseError) {
                    if (failure == null) {
                        failure = releaseError;
                    }
                }
            }
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException closeError) {
                    if (failure == null) {
                        failure = closeError;
                    }
                }
            }
            finish(recordingGeneration, recordingDestination, failure);
        }
    }

    private synchronized boolean shouldFinish(long recordingGeneration) {
        return recordingGeneration != generation
                || state == State.ERROR
                || (stopRequested && queue.isEmpty());
    }

    private void finish(long recordingGeneration, Uri recordingDestination, Throwable failure) {
        Snapshot changed = null;
        boolean shutDown;
        boolean keep;
        synchronized (this) {
            keep = failure == null && accessUnitsWritten > 0;
            if (recordingGeneration == generation) {
                destination = null;
                queue.clear();
                if (failure != null) {
                    state = State.ERROR;
                    message = "Recording failed: " + concise(failure);
                } else if (accessUnitsWritten == 0) {
                    state = State.IDLE;
                    message = "Recording canceled before a keyframe arrived";
                } else if (state != State.ERROR) {
                    state = State.IDLE;
                    message = container == Container.LOSSLESS_MP4
                            ? "Lossless original-stream MP4 saved"
                            : "Raw original-stream H.264 saved";
                }
                settled.countDown();
                changed = snapshotLocked();
            }
            shutDown = closed;
        }
        if (keep) {
            if (!publishMedia(recordingDestination)) {
                deleteMedia(recordingDestination);
                synchronized (this) {
                    if (recordingGeneration == generation) {
                        state = State.ERROR;
                        message = "Recording finished but Android could not publish the media file";
                        changed = snapshotLocked();
                    }
                }
            }
        } else {
            deleteMedia(recordingDestination);
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
                state,
                container,
                bytesWritten,
                accessUnitsWritten,
                droppedUnits,
                startedAtMillis,
                presentationTimeline.firstPresentationUs(),
                presentationTimeline.lastPresentationUs(),
                presentationTimeline.corrections(),
                presentationTimeline.actualFramesPerSecond(),
                message);
    }

    private boolean publishMedia(Uri uri) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            return resolver.update(uri, values, null, null) > 0;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void deleteMedia(Uri uri) {
        try {
            resolver.delete(uri, null, null);
        } catch (RuntimeException ignored) {
            // The failure is already represented in the recorder state.
        }
    }

    private void publish(Snapshot changed) {
        if (changed != null && listener != null) {
            listener.onRecordingChanged(changed);
        }
    }

    static byte[] fourByteStartCode(byte[] nal) {
        int prefix = startCodeLength(nal, 0);
        if (prefix == 4) {
            return nal.clone();
        }
        if (prefix == 3) {
            byte[] result = new byte[nal.length + 1];
            result[3] = 1;
            System.arraycopy(nal, 3, result, 4, nal.length - 3);
            return result;
        }
        byte[] result = new byte[nal.length + 4];
        result[3] = 1;
        System.arraycopy(nal, 0, result, 4, nal.length);
        return result;
    }

    static byte[] firstPayload(H264AccessUnit unit) {
        boolean hasSps = containsNalType(unit.data(), 7);
        boolean hasPps = containsNalType(unit.data(), 8);
        int size = unit.data().length + (hasSps ? 0 : unit.sps().length)
                + (hasPps ? 0 : unit.pps().length);
        byte[] result = new byte[size];
        int offset = 0;
        if (!hasSps) {
            System.arraycopy(unit.sps(), 0, result, offset, unit.sps().length);
            offset += unit.sps().length;
        }
        if (!hasPps) {
            System.arraycopy(unit.pps(), 0, result, offset, unit.pps().length);
            offset += unit.pps().length;
        }
        System.arraycopy(unit.data(), 0, result, offset, unit.data().length);
        return result;
    }

    static boolean containsNalType(byte[] data, int wantedType) {
        for (int i = 0; i + 3 < data.length; i++) {
            int prefix = startCodeLength(data, i);
            if (prefix > 0 && i + prefix < data.length
                    && (data[i + prefix] & 0x1F) == wantedType) {
                return true;
            }
        }
        return false;
    }

    static int startCodeLength(byte[] data, int index) {
        if (index + 2 < data.length && data[index] == 0 && data[index + 1] == 0
                && data[index + 2] == 1) {
            return 3;
        }
        if (index + 3 < data.length && data[index] == 0 && data[index + 1] == 0
                && data[index + 2] == 0 && data[index + 3] == 1) {
            return 4;
        }
        return 0;
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
            shutDownNow = destination == null;
        }
        publish(changed);
        if (shutDownNow) {
            writer.shutdown();
        }
    }
}
