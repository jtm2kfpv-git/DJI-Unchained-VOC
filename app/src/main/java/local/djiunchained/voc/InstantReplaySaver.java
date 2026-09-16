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
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Writes an immutable instant-replay snapshot as a lossless MP4 without blocking USB receive. */
final class InstantReplaySaver implements Closeable {
    interface Listener {
        void onReplaySaveChanged(ReplaySaveLifecycle.Snapshot snapshot);
    }

    private final ContentResolver resolver;
    private final Listener listener;
    private final LongSupplier clockMillis;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ReplaySaveLifecycle lifecycle = new ReplaySaveLifecycle();

    private Uri destination;
    private boolean closed;
    private CountDownLatch settled = new CountDownLatch(0);

    InstantReplaySaver(ContentResolver resolver, Listener listener) {
        this(resolver, listener, () -> System.nanoTime() / 1_000_000);
    }

    InstantReplaySaver(
            ContentResolver resolver,
            Listener listener,
            LongSupplier clockMillis) {
        this.resolver = Objects.requireNonNull(resolver);
        this.listener = listener;
        this.clockMillis = Objects.requireNonNull(clockMillis);
    }

    boolean save(
            Uri destination,
            InstantReplayBuffer.Snapshot clip,
            int width,
            int height) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(clip, "clip");
        if (!clip.ready()) {
            throw new IllegalArgumentException("Replay clip must begin with SPS/PPS and an IDR");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Video dimensions must be positive");
        }

        ReplaySaveLifecycle.Snapshot changed;
        synchronized (this) {
            String message = String.format(
                    java.util.Locale.ROOT,
                    "Saving %.1f seconds of instant replay",
                    clip.retainedDurationUs() / 1_000_000.0);
            if (closed || this.destination != null
                    || !lifecycle.begin(clockMillis.getAsLong(), message)) {
                return false;
            }
            this.destination = destination;
            settled = new CountDownLatch(1);
            changed = lifecycle.snapshot();
        }

        publish(changed);
        writer.execute(() -> writeClip(destination, clip.samples(), width, height));
        return true;
    }

    ReplaySaveLifecycle.Snapshot snapshot() {
        return lifecycle.snapshot();
    }

    boolean awaitSettled(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch current;
        synchronized (this) {
            current = settled;
        }
        return current.await(timeout, unit);
    }

    private void writeClip(
            Uri replayDestination,
            List<InstantReplayBuffer.Sample> samples,
            int width,
            int height) {
        Throwable failure = null;
        ParcelFileDescriptor descriptor = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        MonotonicPresentationTimeline timeline = new MonotonicPresentationTimeline();
        try {
            descriptor = resolver.openFileDescriptor(replayDestination, "rwt");
            if (descriptor == null) {
                throw new IOException("Replay destination could not be opened");
            }

            InstantReplayBuffer.Sample first = samples.get(0);
            muxer = new MediaMuxer(
                    descriptor.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setByteBuffer(
                    "csd-0",
                    ByteBuffer.wrap(OriginalStreamRecorder.fourByteStartCode(
                            first.spsForWriter())));
            format.setByteBuffer(
                    "csd-1",
                    ByteBuffer.wrap(OriginalStreamRecorder.fourByteStartCode(
                            first.ppsForWriter())));
            int trackIndex = muxer.addTrack(format);
            muxer.start();
            muxerStarted = true;

            for (InstantReplayBuffer.Sample sample : samples) {
                byte[] payload = sample.payloadForWriter();
                long presentationUs = timeline.next(sample.arrivalUs());
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                info.set(
                        0,
                        payload.length,
                        presentationUs,
                        sample.keyFrame() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(trackIndex, ByteBuffer.wrap(payload), info);
                lifecycle.sampleWritten(payload.length);
            }

            MediaCodec.BufferInfo end = new MediaCodec.BufferInfo();
            end.set(
                    0,
                    0,
                    timeline.endPresentationUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            muxer.writeSampleData(trackIndex, ByteBuffer.allocate(0), end);
            muxer.stop();
            muxerStarted = false;
        } catch (IOException | RuntimeException error) {
            failure = error;
        } finally {
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (RuntimeException ignored) {
                        // The original failure is retained below.
                    }
                }
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
            finish(replayDestination, timeline, failure);
        }
    }

    private void finish(
            Uri replayDestination,
            MonotonicPresentationTimeline timeline,
            Throwable failure) {
        boolean keep = failure == null && lifecycle.snapshot().accessUnitsWritten() > 0;
        if (keep && !publishMedia(replayDestination)) {
            failure = new IOException("Android could not publish the replay file");
            keep = false;
        }
        if (!keep) {
            deleteMedia(replayDestination);
        }

        ReplaySaveLifecycle.Snapshot changed = failure == null
                ? lifecycle.succeed(timeline)
                : lifecycle.fail(timeline, failure);
        boolean shutDown;
        synchronized (this) {
            destination = null;
            settled.countDown();
            shutDown = closed;
        }
        publish(changed);
        if (shutDown) {
            writer.shutdown();
        }
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
            // The failure is already represented by the saver state.
        }
    }

    private void publish(ReplaySaveLifecycle.Snapshot changed) {
        if (listener != null) {
            listener.onReplaySaveChanged(changed);
        }
    }

    @Override
    public void close() {
        boolean shutDownNow;
        synchronized (this) {
            closed = true;
            shutDownNow = destination == null;
        }
        if (shutDownNow) {
            writer.shutdown();
        }
    }
}
