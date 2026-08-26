package local.n3view;

/** Pure stream-health state machine; Android side effects are performed by the activity. */
final class StreamWatchdog {
    static final long STARTUP_GRACE_MILLIS = 8_000;
    static final long STALL_MILLIS = 3_000;
    static final long DECODER_STEP_DELAY_MILLIS = 3_000;
    static final long REOPEN_STEP_DELAY_MILLIS = 4_000;

    enum Action {
        NONE,
        RESEND_KEEPALIVE,
        RESET_DECODER,
        REOPEN_USB
    }

    record Evaluation(
            Action action,
            long packetAgeMillis,
            long frameAgeMillis,
            int recoveryActions) {
    }

    private boolean active;
    private long activeSince;
    private long lastPacketAt;
    private long lastFrameAt;
    private long lastPacketCount;
    private long lastFrameCount;
    private int stage;
    private long stageChangedAt;
    private int recoveryActions;

    synchronized void setActive(
            boolean enabled,
            long nowMillis,
            long packetCount,
            long frameCount) {
        if (active == enabled) {
            return;
        }
        active = enabled;
        resetBaseline(nowMillis, packetCount, frameCount);
    }

    synchronized void resetBaseline(long nowMillis, long packetCount, long frameCount) {
        activeSince = nowMillis;
        lastPacketAt = nowMillis;
        lastFrameAt = nowMillis;
        lastPacketCount = packetCount;
        lastFrameCount = frameCount;
        stage = 0;
        stageChangedAt = nowMillis;
    }

    synchronized Evaluation evaluate(
            long nowMillis,
            long packetCount,
            long frameCount,
            boolean recoveryEnabled,
            boolean surfaceReady) {
        if (packetCount > lastPacketCount) {
            lastPacketAt = nowMillis;
        }
        lastPacketCount = packetCount;
        if (frameCount > lastFrameCount) {
            lastFrameAt = nowMillis;
            stage = 0;
            stageChangedAt = nowMillis;
        }
        lastFrameCount = frameCount;

        long packetAge = Math.max(0, nowMillis - lastPacketAt);
        long frameAge = Math.max(0, nowMillis - lastFrameAt);
        Action action = Action.NONE;

        if (active && recoveryEnabled && surfaceReady
                && nowMillis - activeSince >= STARTUP_GRACE_MILLIS
                && frameAge >= STALL_MILLIS) {
            if (stage == 0) {
                stage = 1;
                stageChangedAt = nowMillis;
                action = Action.RESEND_KEEPALIVE;
            } else if (stage == 1
                    && nowMillis - stageChangedAt >= DECODER_STEP_DELAY_MILLIS) {
                stage = 2;
                stageChangedAt = nowMillis;
                action = Action.RESET_DECODER;
            } else if (stage == 2
                    && nowMillis - stageChangedAt >= REOPEN_STEP_DELAY_MILLIS) {
                stage = 3;
                stageChangedAt = nowMillis;
                action = Action.REOPEN_USB;
            }
        }

        if (action != Action.NONE) {
            recoveryActions++;
        }
        return new Evaluation(action, packetAge, frameAge, recoveryActions);
    }
}
