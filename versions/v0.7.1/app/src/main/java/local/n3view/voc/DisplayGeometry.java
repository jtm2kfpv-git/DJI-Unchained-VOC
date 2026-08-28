package local.n3view.voc;

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

    public enum SourceAspect {
        AUTO("AUTO"),
        WIDE_16_9("16:9"),
        CLASSIC_4_3("4:3");

        private final String label;

        SourceAspect(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public SourceAspect next() {
            SourceAspect[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public record Size(int width, int height) {
    }

    public static Size sourceSize(int decodedWidth, int decodedHeight, SourceAspect aspect) {
        return switch (aspect) {
            case AUTO -> new Size(Math.max(1, decodedWidth), Math.max(1, decodedHeight));
            case WIDE_16_9 -> new Size(16, 9);
            case CLASSIC_4_3 -> new Size(4, 3);
        };
    }

    public static Size shortsViewport(int containerWidth, int containerHeight) {
        return calculate(containerWidth, containerHeight, 9, 16, Mode.FIT);
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
