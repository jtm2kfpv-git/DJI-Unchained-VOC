package local.djiunchained.voc;

/** Converts capture-arrival timestamps into a zero-based, strictly increasing MP4 timeline. */
final class MonotonicPresentationTimeline {
    private long firstArrivalUs = -1;
    private long lastPresentationUs = -1;
    private long lastFrameDurationUs = 33_333;
    private long corrections;
    private long samples;

    synchronized void reset() {
        firstArrivalUs = -1;
        lastPresentationUs = -1;
        lastFrameDurationUs = 33_333;
        corrections = 0;
        samples = 0;
    }

    synchronized long next(long arrivalUs) {
        if (firstArrivalUs < 0) {
            firstArrivalUs = arrivalUs;
        }
        long candidate = Math.max(0, arrivalUs - firstArrivalUs);
        long priorPresentationUs = lastPresentationUs;
        if (candidate <= priorPresentationUs) {
            candidate = lastPresentationUs + 1;
            corrections++;
        }
        if (priorPresentationUs >= 0) {
            long observed = candidate - priorPresentationUs;
            if (observed >= 1_000 && observed <= 250_000) {
                lastFrameDurationUs = observed;
            }
        }
        lastPresentationUs = candidate;
        samples++;
        return candidate;
    }

    synchronized long endPresentationUs() {
        return lastPresentationUs < 0 ? 0 : lastPresentationUs + lastFrameDurationUs;
    }

    synchronized long firstPresentationUs() {
        return samples == 0 ? -1 : 0;
    }

    synchronized long lastPresentationUs() {
        return lastPresentationUs;
    }

    synchronized long corrections() {
        return corrections;
    }

    synchronized long samples() {
        return samples;
    }

    synchronized double actualFramesPerSecond() {
        if (samples < 2 || lastPresentationUs <= 0) {
            return 0;
        }
        return (samples - 1) * 1_000_000.0 / lastPresentationUs;
    }
}
