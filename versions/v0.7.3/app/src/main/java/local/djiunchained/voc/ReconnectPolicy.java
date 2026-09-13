package local.djiunchained.voc;

/** Deterministic capped backoff kept independent from Android so it is unit-testable. */
final class ReconnectPolicy {
    private static final long[] DELAYS_MILLIS = {1_000, 2_000, 5_000, 10_000};
    private int failureCount;

    record Attempt(int number, long delayMillis) {
    }

    Attempt nextAttempt() {
        int number = ++failureCount;
        int delayIndex = Math.min(number - 1, DELAYS_MILLIS.length - 1);
        return new Attempt(number, DELAYS_MILLIS[delayIndex]);
    }

    void reset() {
        failureCount = 0;
    }

    int failureCount() {
        return failureCount;
    }
}
