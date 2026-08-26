package local.n3view;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import local.n3view.protocol.H264AccessUnit;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class AvcSurfaceDecoder implements Closeable {
    interface Listener {
        void onDecoderStatus(String message);
        void onDecoderFailure(String message, Throwable error);
        void onVideoFormat(int width, int height);
    }

    private final Listener listener;
    private final ExecutorService outputWorker = Executors.newSingleThreadExecutor();
    private final AtomicLong codecGeneration = new AtomicLong();
    private Surface surface;
    private MediaCodec codec;
    private byte[] configuredSps;
    private byte[] configuredPps;
    private boolean receivedKeyFrame;
    private final AtomicLong queuedFrames = new AtomicLong();
    private final AtomicLong renderedFrames = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();

    record Stats(long queued, long rendered, long dropped) {
    }

    AvcSurfaceDecoder(Listener listener) {
        this.listener = listener;
    }

    synchronized void setSurface(Surface surface) {
        stopCodec();
        this.surface = surface;
        listener.onDecoderStatus("Display surface ready; waiting for SPS/PPS and IDR");
    }

    synchronized void clearSurface() {
        stopCodec();
        surface = null;
    }

    synchronized void resetStream() {
        stopCodec();
        queuedFrames.set(0);
        renderedFrames.set(0);
        droppedFrames.set(0);
        listener.onDecoderStatus("Stream reset; waiting for fresh SPS/PPS and IDR");
    }

    synchronized void queue(H264AccessUnit unit) {
        if (surface == null || !surface.isValid()) {
            droppedFrames.incrementAndGet();
            return;
        }
        boolean formatChanged = codec != null
                && unit.keyFrame()
                && unit.sps() != null
                && unit.pps() != null
                && (!Arrays.equals(configuredSps, unit.sps()) || !Arrays.equals(configuredPps, unit.pps()));
        if (formatChanged) {
            listener.onDecoderStatus("H.264 parameters changed; restarting decoder");
            stopCodec();
        }
        if (codec == null) {
            if (unit.sps() == null || unit.pps() == null) {
                droppedFrames.incrementAndGet();
                return;
            }
            try {
                startCodec(unit.sps(), unit.pps());
            } catch (IOException | RuntimeException error) {
                listener.onDecoderFailure("Could not start Android H.264 decoder", error);
                stopCodec();
                return;
            }
        }
        if (!receivedKeyFrame && !unit.keyFrame()) {
            droppedFrames.incrementAndGet();
            return;
        }
        receivedKeyFrame |= unit.keyFrame();

        try {
            int index = codec.dequeueInputBuffer(0);
            if (index < 0) {
                droppedFrames.incrementAndGet();
                return;
            }
            ByteBuffer input = codec.getInputBuffer(index);
            if (input == null || input.capacity() < unit.data().length) {
                codec.queueInputBuffer(index, 0, 0, System.nanoTime() / 1_000, 0);
                droppedFrames.incrementAndGet();
                return;
            }
            input.clear();
            input.put(unit.data());
            int flags = unit.keyFrame() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            codec.queueInputBuffer(index, 0, unit.data().length, System.nanoTime() / 1_000, flags);
            queuedFrames.incrementAndGet();
        } catch (IllegalStateException error) {
            listener.onDecoderFailure("H.264 input queue failed", error);
            stopCodec();
        }
    }

    Stats stats() {
        return new Stats(queuedFrames.get(), renderedFrames.get(), droppedFrames.get());
    }

    private void startCodec(byte[] sps, byte[] pps) throws IOException {
        try {
            configureCodec(sps, pps, Build.VERSION.SDK_INT >= 30);
        } catch (IllegalArgumentException | MediaCodec.CodecException error) {
            if (Build.VERSION.SDK_INT < 30) {
                throw error;
            }
            listener.onDecoderStatus("Decoder rejected low-latency mode; retrying normally");
            configureCodec(sps, pps, false);
        }
    }

    private void configureCodec(byte[] sps, byte[] pps, boolean lowLatency) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (lowLatency) {
            // MediaFormat.KEY_LOW_LATENCY is the literal "low-latency". Using
            // the stable wire key keeps API 29 class loading safe; callers only
            // request it after an SDK >= 30 check and retry without it on reject.
            format.setInteger("low-latency", 1);
        }
        MediaCodec candidate = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            candidate.configure(format, surface, null, 0);
            candidate.start();
        } catch (RuntimeException error) {
            candidate.release();
            throw error;
        }
        codec = candidate;
        configuredSps = sps.clone();
        configuredPps = pps.clone();
        receivedKeyFrame = false;
        long generation = codecGeneration.incrementAndGet();
        outputWorker.execute(() -> drainOutput(generation));
        listener.onDecoderStatus("Android H.264 decoder started");
    }

    private void drainOutput(long generation) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (generation == codecGeneration.get()) {
            MediaCodec current;
            synchronized (this) {
                current = codec;
            }
            if (current == null) {
                return;
            }
            try {
                int index = current.dequeueOutputBuffer(info, 10_000);
                if (index >= 0) {
                    current.releaseOutputBuffer(index, true);
                    renderedFrames.incrementAndGet();
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    reportOutputFormat(current.getOutputFormat());
                }
            } catch (IllegalStateException error) {
                if (generation == codecGeneration.get()) {
                    listener.onDecoderFailure("H.264 output failed", error);
                }
                return;
            }
        }
    }

    private void reportOutputFormat(MediaFormat format) {
        int width = format.containsKey(MediaFormat.KEY_WIDTH)
                ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
        int height = format.containsKey(MediaFormat.KEY_HEIGHT)
                ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
        }
        if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
            height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
        }
        if (width > 0 && height > 0) {
            listener.onVideoFormat(width, height);
            listener.onDecoderStatus("Video active: " + width + "x" + height);
        } else {
            listener.onDecoderStatus("Video active; decoder did not report dimensions");
        }
    }

    private synchronized void stopCodec() {
        codecGeneration.incrementAndGet();
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
            codec = null;
        }
        configuredSps = null;
        configuredPps = null;
        receivedKeyFrame = false;
    }

    @Override
    public void close() {
        synchronized (this) {
            stopCodec();
        }
        outputWorker.shutdownNow();
    }
}
