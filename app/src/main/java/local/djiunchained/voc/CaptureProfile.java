package local.djiunchained.voc;

/** Pure, unit-testable recording-output configuration for the v0.7 pipeline. */
public record CaptureProfile(
        OutputFormat outputFormat,
        int width,
        int height,
        FrameRate frameRate,
        float horizontalCrop,
        long maximumDurationMillis,
        long maximumBytes) {

    public static final int SHORTS_WIDTH = 720;
    public static final int SHORTS_HEIGHT = 1280;
    public static final long DEFAULT_MAXIMUM_DURATION_MILLIS = 30L * 60L * 1_000L;
    public static final long DEFAULT_MAXIMUM_BYTES = 3_800_000_000L;

    public enum OutputFormat {
        ORIGINAL_STREAM("Original stream"),
        SHORTS_9_16("9:16 Shorts");

        private final String label;

        OutputFormat(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public OutputFormat next() {
            return this == ORIGINAL_STREAM ? SHORTS_9_16 : ORIGINAL_STREAM;
        }
    }

    public enum FrameRate {
        FPS_30(30),
        FPS_60(60);

        private final int framesPerSecond;

        FrameRate(int framesPerSecond) {
            this.framesPerSecond = framesPerSecond;
        }

        public int framesPerSecond() {
            return framesPerSecond;
        }

        public FrameRate next() {
            FrameRate[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public CaptureProfile {
        if (outputFormat == null || frameRate == null) {
            throw new IllegalArgumentException("Output format and frame rate are required");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Output dimensions must be positive");
        }
        if (outputFormat == OutputFormat.SHORTS_9_16 && width >= height) {
            throw new IllegalArgumentException("Shorts output must be portrait");
        }
        if (horizontalCrop < -1f || horizontalCrop > 1f) {
            throw new IllegalArgumentException("Horizontal crop must be between -1 and 1");
        }
        if (maximumDurationMillis <= 0 || maximumBytes <= 0) {
            throw new IllegalArgumentException("Recording limits must be positive");
        }
    }

    public static CaptureProfile shorts(FrameRate frameRate, float horizontalCrop) {
        return new CaptureProfile(
                OutputFormat.SHORTS_9_16,
                SHORTS_WIDTH,
                SHORTS_HEIGHT,
                frameRate,
                clampCrop(horizontalCrop),
                DEFAULT_MAXIMUM_DURATION_MILLIS,
                DEFAULT_MAXIMUM_BYTES);
    }

    public static float clampCrop(float crop) {
        return Math.max(-1f, Math.min(1f, crop));
    }

    public boolean limitReached(long elapsedMillis, long bytesWritten) {
        return elapsedMillis >= maximumDurationMillis || bytesWritten >= maximumBytes;
    }
}
