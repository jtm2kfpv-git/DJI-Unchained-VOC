package local.n3view;

import local.n3view.protocol.LogicLinkDecoder;
import local.n3view.protocol.LogicLinkPacket;
import local.n3view.protocol.N3ControlPackets;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class N3Session implements Closeable {
    interface Listener {
        void onStatus(String message);
        void onVideo(byte[] bytes);
        void onStats(long videoBytes, long videoPackets, long discardedBytes);
        void onFailure(String message, Throwable error);
    }

    private final InputStream input;
    private final OutputStream output;
    private final Listener listener;
    private final LogicLinkDecoder decoder = new LogicLinkDecoder();
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong videoBytes = new AtomicLong();
    private final AtomicLong videoPackets = new AtomicLong();
    private final Object writeLock = new Object();

    N3Session(InputStream input, OutputStream output, Listener listener) {
        this.input = input;
        this.output = output;
        this.listener = listener;
    }

    void start() {
        listener.onStatus("USB accessory open; starting N3 logiclink session");
        workers.execute(this::readLoop);
        workers.scheduleWithFixedDelay(this::sendKeepaliveSafely, 0, 5, TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(
                () -> listener.onStats(videoBytes.get(), videoPackets.get(), decoder.discardedBytes()),
                1, 1, TimeUnit.SECONDS);
    }

    private void readLoop() {
        byte[] readBuffer = new byte[64 * 1024];
        try {
            while (!closed.get()) {
                int count = input.read(readBuffer);
                if (count < 0) {
                    throw new IOException("USB accessory reached end of stream");
                }
                if (count == 0) {
                    continue;
                }
                decoder.accept(readBuffer, 0, count, this::handlePacket);
            }
        } catch (IOException | RuntimeException error) {
            if (!closed.get()) {
                listener.onFailure("USB read failed", error);
                close();
            }
        }
    }

    private void handlePacket(LogicLinkPacket packet) {
        if (packet.port() == LogicLinkDecoder.VIDEO_PORT) {
            videoPackets.incrementAndGet();
            videoBytes.addAndGet(packet.payload().length);
            listener.onVideo(packet.payload());
        }
    }

    private void sendKeepaliveSafely() {
        if (closed.get()) {
            return;
        }
        try {
            synchronized (writeLock) {
                for (byte[] packet : N3ControlPackets.copies()) {
                    output.write(packet);
                }
                output.flush();
            }
        } catch (IOException error) {
            if (!closed.get()) {
                listener.onFailure("N3 video-start/keepalive write failed", error);
                close();
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        workers.shutdownNow();
        try {
            input.close();
        } catch (IOException ignored) {
        }
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }
}
