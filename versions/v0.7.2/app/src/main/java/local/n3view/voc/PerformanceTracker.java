package local.n3view.voc;

import java.util.ArrayDeque;

/** Rolling bitrate/FPS and frame-gap metrics, independent from Android UI code. */
final class PerformanceTracker {
    record Sample(long timeMillis, long bytes, long frames) {
    }

    record Snapshot(
            double currentMegabitsPerSecond,
            double averageMegabitsPerSecond,
            double minimumMegabitsPerSecond,
            double maximumMegabitsPerSecond,
            double fps1Second,
            double fps5Seconds,
            double fps30Seconds,
            long maximumFrameGapMillis,
            int sampleCount) {
    }

    private static final long HISTORY_MILLIS = 31_000;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double minimumMegabitsPerSecond = Double.POSITIVE_INFINITY;
    private double maximumMegabitsPerSecond;
    private long previousFrameAt;
    private long maximumFrameGapMillis;

    synchronized void addSample(long nowMillis, long totalBytes, long totalFrames) {
        Sample previous = samples.peekLast();
        if (previous != null && nowMillis > previous.timeMillis()) {
            double current = megabits(previous, new Sample(nowMillis, totalBytes, totalFrames));
            minimumMegabitsPerSecond = Math.min(minimumMegabitsPerSecond, current);
            maximumMegabitsPerSecond = Math.max(maximumMegabitsPerSecond, current);
        }
        samples.addLast(new Sample(nowMillis, totalBytes, totalFrames));
        while (samples.size() > 2
                && nowMillis - samples.peekFirst().timeMillis() > HISTORY_MILLIS) {
            samples.removeFirst();
        }
    }

    synchronized void onFrame(long nowMillis) {
        if (previousFrameAt > 0 && nowMillis >= previousFrameAt) {
            maximumFrameGapMillis = Math.max(maximumFrameGapMillis, nowMillis - previousFrameAt);
        }
        previousFrameAt = nowMillis;
    }

    synchronized Snapshot snapshot() {
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        double current = samples.size() < 2 ? 0 : megabits(secondLast(), last);
        double average = samples.size() < 2 ? 0 : megabits(first, last);
        return new Snapshot(
                current,
                average,
                Double.isInfinite(minimumMegabitsPerSecond) ? 0 : minimumMegabitsPerSecond,
                maximumMegabitsPerSecond,
                frameRate(1_000),
                frameRate(5_000),
                frameRate(30_000),
                maximumFrameGapMillis,
                samples.size());
    }

    private Sample secondLast() {
        Sample last = samples.removeLast();
        Sample previous = samples.peekLast();
        samples.addLast(last);
        return previous;
    }

    private double frameRate(long windowMillis) {
        if (samples.size() < 2) {
            return 0;
        }
        Sample last = samples.peekLast();
        Sample start = samples.peekFirst();
        for (Sample candidate : samples) {
            if (last.timeMillis() - candidate.timeMillis() <= windowMillis) {
                start = candidate;
                break;
            }
        }
        long elapsed = last.timeMillis() - start.timeMillis();
        return elapsed <= 0 ? 0
                : Math.max(0, last.frames() - start.frames()) * 1_000.0 / elapsed;
    }

    private static double megabits(Sample start, Sample end) {
        long elapsed = end.timeMillis() - start.timeMillis();
        return elapsed <= 0 ? 0
                : Math.max(0, end.bytes() - start.bytes()) * 8.0 / elapsed / 1_000.0;
    }
}
