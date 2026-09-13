package local.djiunchained.voc;

import android.content.ContentResolver;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
import android.view.Surface;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Surface-fed H.264/MP4 encoder used by the v0.7 Shorts capture path.
 *
 * <p>The caller supplies frames through {@link #inputSurface()}. The encoder itself is
 * independent of the producer; v0.7.2 currently connects a consent-gated MediaProjection
 * virtual display, while the planned direct GPU crop can use the same input surface later.</p>
 */
public final class DirectMp4Encoder implements AutoCloseable {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long MINIMUM_AVAILABLE_BYTES = 500L * 1024L * 1024L;
    private static final long PROGRESS_INTERVAL_MILLIS = 500L;

    public interface Listener {
        void onEncoderStarted(String codecName, CaptureProfile profile);

        void onEncoderProgress(long elapsedMillis, long encodedBytes);

        void onEncoderFinished(long elapsedMillis, long encodedBytes, String reason);

        void onEncoderFailed(String message, Throwable error);
    }

    private final ContentResolver resolver;
    private final Uri outputUri;
    private final CaptureProfile profile;
    private final Listener listener;

    private ParcelFileDescriptor outputDescriptor;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private Surface inputSurface;
    private Thread drainThread;
    private volatile boolean stopRequested;
    private volatile boolean discardOutput;
    private volatile boolean started;
    private volatile boolean completed;
    private volatile long startedAtMillis;
    private volatile long encodedBytes;
    private volatile long encodedFrames;
    private final MonotonicPresentationTimeline presentationTimeline =
            new MonotonicPresentationTimeline();

    public DirectMp4Encoder(
            ContentResolver resolver,
            Uri outputUri,
            CaptureProfile profile,
            Listener listener) {
        this.resolver = Objects.requireNonNull(resolver);
        this.outputUri = Objects.requireNonNull(outputUri);
        this.profile = Objects.requireNonNull(profile);
        this.listener = Objects.requireNonNull(listener);
        if (profile.outputFormat() != CaptureProfile.OutputFormat.SHORTS_9_16) {
            throw new IllegalArgumentException("Direct MP4 encoding requires a 9:16 profile");
        }
    }

    public synchronized void start() throws IOException {
        if (started || drainThread != null) {
            throw new IllegalStateException("Encoder already started");
        }
        try {
            outputDescriptor = resolver.openFileDescriptor(outputUri, "rwt");
            if (outputDescriptor == null) {
                throw new FileNotFoundException("Selected destination could not be opened");
            }
            long availableBytes = availableBytes(outputDescriptor);
            if (availableBytes >= 0 && availableBytes < MINIMUM_AVAILABLE_BYTES) {
                throw new IOException("Selected destination has less than 500 MB available");
            }

            MediaFormat format = MediaFormat.createVideoFormat(
                    MIME, profile.width(), profile.height());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE,
                    profile.frameRate().framesPerSecond());
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,
                    profile.frameRate().framesPerSecond());
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            muxer = new MediaMuxer(
                    outputDescriptor.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            codec.start();
            startedAtMillis = SystemClock.elapsedRealtime();
            started = true;
            listener.onEncoderStarted(codec.getName(), profile);
            drainThread = new Thread(this::drainLoop, "VOC-direct-mp4-drain");
            drainThread.start();
        } catch (IOException | RuntimeException error) {
            cleanup(false);
            throw error;
        }
    }

    public synchronized Surface inputSurface() {
        if (!started || inputSurface == null) {
            throw new IllegalStateException("Encoder has not started");
        }
        return inputSurface;
    }

    public synchronized void stopSafely() {
        if (!started || stopRequested) {
            return;
        }
        stopRequested = true;
        try {
            codec.signalEndOfInputStream();
        } catch (IllegalStateException error) {
            listener.onEncoderFailed("Could not signal the end of the MP4 stream", error);
        }
    }

    public synchronized void cancel() {
        discardOutput = true;
        stopSafely();
    }

    public boolean isStarted() {
        return started;
    }

    public long encodedFrames() {
        return encodedFrames;
    }

    public long timestampCorrections() {
        return presentationTimeline.corrections();
    }

    public long firstPresentationUs() {
        return encodedFrames == 0 ? -1 : 0;
    }

    public long lastPresentationUs() {
        return presentationTimeline.lastPresentationUs();
    }

    public double actualFramesPerSecond() {
        return presentationTimeline.actualFramesPerSecond();
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean muxerStarted = false;
        boolean limitSignalled = false;
        long lastProgressAt = 0;
        String finishReason = "Stopped by user";
        try {
            while (true) {
                int outputIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IllegalStateException("Encoder output format changed twice");
                    }
                    int track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    drainSamples(info, track);
                    return;
                }
                if (outputIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outputIndex);
                    if (output == null) {
                        throw new IllegalStateException("Encoder returned a null output buffer");
                    }
                    boolean codecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (info.size > 0 && !codecConfig) {
                        if (!muxerStarted) {
                            throw new IllegalStateException("Encoded sample arrived before muxer start");
                        }
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        sanitizePresentationTime(info);
                        muxer.writeSampleData(0, output, info);
                        encodedBytes += info.size;
                        encodedFrames++;
                    }
                    boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);

                    long elapsed = Math.max(0, SystemClock.elapsedRealtime() - startedAtMillis);
                    if (elapsed - lastProgressAt >= PROGRESS_INTERVAL_MILLIS) {
                        lastProgressAt = elapsed;
                        listener.onEncoderProgress(elapsed, encodedBytes);
                    }
                    if (!limitSignalled && profile.limitReached(elapsed, encodedBytes)) {
                        limitSignalled = true;
                        finishReason = encodedBytes >= profile.maximumBytes()
                                ? "Maximum recording size reached"
                                : "Maximum recording duration reached";
                        stopSafely();
                    }
                    if (endOfStream) {
                        if (discardOutput) {
                            completed = false;
                            cleanup(false);
                            listener.onEncoderFailed(
                                    "MP4 capture was canceled; incomplete output was removed",
                                    new IOException("Capture canceled"));
                            return;
                        }
                        completed = true;
                        cleanup(true);
                        reportCompletion(elapsed, finishReason);
                        return;
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            cleanup(false);
            listener.onEncoderFailed("Direct MP4 encoding failed", error);
        }
    }

    /** Continue draining after the muxer track has been created. */
    private void drainSamples(MediaCodec.BufferInfo info, int trackIndex) throws IOException {
        long lastProgressAt = 0;
        boolean limitSignalled = false;
        String finishReason = "Stopped by user";
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, 10_000);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                throw new IllegalStateException("Encoder output format changed twice");
            }
            if (outputIndex < 0) {
                continue;
            }
            ByteBuffer output = codec.getOutputBuffer(outputIndex);
            if (output == null) {
                throw new IllegalStateException("Encoder returned a null output buffer");
            }
            boolean codecConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (info.size > 0 && !codecConfig) {
                output.position(info.offset);
                output.limit(info.offset + info.size);
                sanitizePresentationTime(info);
                muxer.writeSampleData(trackIndex, output, info);
                encodedBytes += info.size;
                encodedFrames++;
            }
            boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(outputIndex, false);

            long elapsed = Math.max(0, SystemClock.elapsedRealtime() - startedAtMillis);
            if (elapsed - lastProgressAt >= PROGRESS_INTERVAL_MILLIS) {
                lastProgressAt = elapsed;
                listener.onEncoderProgress(elapsed, encodedBytes);
            }
            if (!limitSignalled && profile.limitReached(elapsed, encodedBytes)) {
                limitSignalled = true;
                finishReason = encodedBytes >= profile.maximumBytes()
                        ? "Maximum recording size reached"
                        : "Maximum recording duration reached";
                stopSafely();
            }
            if (endOfStream) {
                if (discardOutput) {
                    completed = false;
                    cleanup(false);
                    listener.onEncoderFailed(
                            "MP4 capture was canceled; incomplete output was removed",
                            new IOException("Capture canceled"));
                    return;
                }
                completed = true;
                cleanup(true);
                reportCompletion(elapsed, finishReason);
                return;
            }
        }
    }

    private void reportCompletion(long elapsedMillis, String finishReason) {
        if (completed) {
            listener.onEncoderFinished(elapsedMillis, encodedBytes, finishReason);
        } else {
            listener.onEncoderFailed(
                    "MP4 could not be finalized; incomplete output was removed",
                    new IOException("MediaMuxer finalization failed"));
        }
    }

    private void sanitizePresentationTime(MediaCodec.BufferInfo info) {
        info.presentationTimeUs = presentationTimeline.next(info.presentationTimeUs);
    }

    private static long availableBytes(ParcelFileDescriptor descriptor) {
        try {
            StructStatVfs stats = Os.fstatvfs(descriptor.getFileDescriptor());
            return Math.multiplyExact(stats.f_bavail, stats.f_frsize);
        } catch (ErrnoException | ArithmeticException error) {
            return -1;
        }
    }

    private synchronized void cleanup(boolean keepOutput) {
        started = false;
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
                // A codec failure is already being reported by the caller.
            }
            codec.release();
            codec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (muxer != null) {
            if (keepOutput) {
                try {
                    muxer.stop();
                } catch (IllegalStateException error) {
                    completed = false;
                }
            }
            muxer.release();
            muxer = null;
        }
        if (outputDescriptor != null) {
            try {
                outputDescriptor.close();
            } catch (IOException ignored) {
                completed = false;
            }
            outputDescriptor = null;
        }
        drainThread = null;
        if (!keepOutput || !completed) {
            deleteIncompleteOutput();
        }
    }

    private void deleteIncompleteOutput() {
        try {
            resolver.delete(outputUri, null, null);
        } catch (RuntimeException ignored) {
            // Some document providers do not permit deletion; the failure remains reported.
        }
    }

    @Override
    public void close() {
        stopSafely();
    }
}
