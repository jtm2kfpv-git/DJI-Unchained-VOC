package local.djiunchained.voc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import local.djiunchained.voc.protocol.H264AccessUnit;

/**
 * Memory-bounded buffer for a future lossless instant-replay save path.
 *
 * <p>The buffer always begins with a usable IDR access unit carrying SPS/PPS metadata. When a
 * duration or memory limit removes that starting point, dependent frames are discarded until the
 * next usable IDR arrives. This guarantees that every ready snapshot can start an independent
 * recording without decoding or re-encoding the incoming stream.</p>
 */
final class InstantReplayBuffer {
    record Sample(byte[] data, boolean keyFrame, byte[] sps, byte[] pps, long arrivalUs) {
        Sample {
            data = copyRequired(data, "data");
            sps = copyOptional(sps);
            pps = copyOptional(pps);
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public byte[] sps() {
            return copyOptional(sps);
        }

        @Override
        public byte[] pps() {
            return copyOptional(pps);
        }

        int retainedBytes() {
            return data.length + length(sps) + length(pps);
        }

        boolean usableStart() {
            return keyFrame && sps != null && sps.length > 0 && pps != null && pps.length > 0;
        }
    }

    record Snapshot(
            List<Sample> samples,
            long retainedBytes,
            long retainedDurationUs,
            long evictedUnits,
            long evictedBytes) {
        Snapshot {
            samples = List.copyOf(samples);
        }

        boolean ready() {
            return !samples.isEmpty() && samples.get(0).usableStart();
        }
    }

    private final long maximumDurationUs;
    private final long maximumBytes;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    private long retainedBytes;
    private long evictedUnits;
    private long evictedBytes;

    InstantReplayBuffer(long maximumDurationUs, long maximumBytes) {
        if (maximumDurationUs <= 0) {
            throw new IllegalArgumentException("maximumDurationUs must be positive");
        }
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumDurationUs = maximumDurationUs;
        this.maximumBytes = maximumBytes;
    }

    synchronized void offer(H264AccessUnit unit, long arrivalUs) {
        Objects.requireNonNull(unit, "unit");
        Sample sample = new Sample(
                unit.data(), unit.keyFrame(), unit.sps(), unit.pps(), arrivalUs);
        samples.addLast(sample);
        retainedBytes += sample.retainedBytes();

        while (exceedsLimits()) {
            evictFirst();
        }
        while (!samples.isEmpty() && !samples.peekFirst().usableStart()) {
            evictFirst();
        }
    }

    synchronized Snapshot snapshot() {
        List<Sample> copy = new ArrayList<>(samples.size());
        for (Sample sample : samples) {
            copy.add(new Sample(
                    sample.data, sample.keyFrame, sample.sps, sample.pps, sample.arrivalUs));
        }
        return new Snapshot(
                copy, retainedBytes, retainedDurationUs(), evictedUnits, evictedBytes);
    }

    synchronized void clear() {
        samples.clear();
        retainedBytes = 0;
        evictedUnits = 0;
        evictedBytes = 0;
    }

    private boolean exceedsLimits() {
        if (samples.isEmpty()) {
            return false;
        }
        return retainedBytes > maximumBytes || retainedDurationUs() > maximumDurationUs;
    }

    private long retainedDurationUs() {
        if (samples.size() < 2) {
            return 0;
        }
        return Math.max(0, samples.peekLast().arrivalUs - samples.peekFirst().arrivalUs);
    }

    private void evictFirst() {
        Sample removed = samples.removeFirst();
        int removedBytes = removed.retainedBytes();
        retainedBytes -= removedBytes;
        evictedUnits++;
        evictedBytes += removedBytes;
    }

    private static byte[] copyRequired(byte[] value, String label) {
        return Objects.requireNonNull(value, label).clone();
    }

    private static byte[] copyOptional(byte[] value) {
        return value == null ? null : value.clone();
    }

    private static int length(byte[] value) {
        return value == null ? 0 : value.length;
    }
}
