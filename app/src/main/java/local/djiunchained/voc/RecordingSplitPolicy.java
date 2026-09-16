package local.djiunchained.voc;

import java.util.concurrent.TimeUnit;

/** Decides when a lossless recording may roll over to a new independently playable part. */
final class RecordingSplitPolicy {
    private final long segmentDurationUs;

    private RecordingSplitPolicy(long segmentDurationUs) {
        this.segmentDurationUs = segmentDurationUs;
    }

    static RecordingSplitPolicy disabled() {
        return new RecordingSplitPolicy(0);
    }

    static RecordingSplitPolicy minutes(long minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("minutes must be positive");
        }
        return new RecordingSplitPolicy(TimeUnit.MINUTES.toMicros(minutes));
    }

    static RecordingSplitPolicy durationUs(long durationUs) {
        if (durationUs <= 0) {
            throw new IllegalArgumentException("durationUs must be positive");
        }
        return new RecordingSplitPolicy(durationUs);
    }

    boolean enabled() {
        return segmentDurationUs > 0;
    }

    long segmentDurationUs() {
        return segmentDurationUs;
    }

    boolean shouldStartNextPart(
            long segmentStartPresentationUs,
            long samplePresentationUs,
            boolean keyFrame) {
        if (!enabled() || !keyFrame || segmentStartPresentationUs < 0) {
            return false;
        }
        return samplePresentationUs - segmentStartPresentationUs >= segmentDurationUs;
    }

    long overdueUs(long segmentStartPresentationUs, long samplePresentationUs) {
        if (!enabled() || segmentStartPresentationUs < 0) {
            return 0;
        }
        return Math.max(
                0, samplePresentationUs - segmentStartPresentationUs - segmentDurationUs);
    }
}
