package local.djiunchained.voc;

/** Supported replay settings and defensive preference normalization. */
final class InstantReplayConfiguration {
    static final int DEFAULT_DURATION_SECONDS = 30;
    static final long MAXIMUM_BUFFER_BYTES = 128L * 1024 * 1024;
    private static final int[] DURATIONS_SECONDS = {15, 30, 60};

    private InstantReplayConfiguration() {
    }

    static int normalizeDurationSeconds(int stored) {
        for (int value : DURATIONS_SECONDS) {
            if (stored == value) {
                return value;
            }
        }
        return DEFAULT_DURATION_SECONDS;
    }

    static int nextDurationSeconds(int current) {
        for (int index = 0; index < DURATIONS_SECONDS.length; index++) {
            if (DURATIONS_SECONDS[index] == current) {
                return DURATIONS_SECONDS[(index + 1) % DURATIONS_SECONDS.length];
            }
        }
        return DEFAULT_DURATION_SECONDS;
    }

    static InstantReplayBuffer createBuffer(int durationSeconds) {
        int normalized = normalizeDurationSeconds(durationSeconds);
        return new InstantReplayBuffer(
                normalized * 1_000_000L,
                MAXIMUM_BUFFER_BYTES);
    }
}
