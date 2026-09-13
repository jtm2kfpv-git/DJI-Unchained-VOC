package local.djiunchained.voc;

/** Pure layout rule for the top-anchored square offline artwork. */
final class BackdropGeometry {
    private BackdropGeometry() {
    }

    static int topArtworkHeight(int rootWidth, int rootHeight) {
        if (rootWidth <= 0 || rootHeight <= 0) {
            throw new IllegalArgumentException("Root dimensions must be positive");
        }
        return Math.min(rootWidth, rootHeight);
    }
}
