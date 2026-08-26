package local.n3view;

/** Pure display-sizing logic kept separate so every aspect mode is unit-testable. */
public final class DisplayGeometry {
    private DisplayGeometry() {
    }

    public enum Mode {
        FIT,
        FILL,
        STRETCH;

        public Mode next() {
            Mode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public record Size(int width, int height) {
    }

    public static Size calculate(
            int containerWidth,
            int containerHeight,
            int sourceWidth,
            int sourceHeight,
            Mode mode) {
        if (containerWidth <= 0 || containerHeight <= 0) {
            return new Size(1, 1);
        }
        if (mode == Mode.STRETCH || sourceWidth <= 0 || sourceHeight <= 0) {
            return new Size(containerWidth, containerHeight);
        }
        double widthScale = containerWidth / (double) sourceWidth;
        double heightScale = containerHeight / (double) sourceHeight;
        double scale = mode == Mode.FILL
                ? Math.max(widthScale, heightScale)
                : Math.min(widthScale, heightScale);
        return new Size(
                Math.max(1, (int) Math.round(sourceWidth * scale)),
                Math.max(1, (int) Math.round(sourceHeight * scale)));
    }
}
