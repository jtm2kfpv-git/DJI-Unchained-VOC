package local.djiunchained.voc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class BackdropGeometryTest {
    @Test
    public void squareArtworkUsesTopScreenDimensionInEitherOrientation() {
        assertEquals(1080, BackdropGeometry.topArtworkHeight(1080, 2400));
        assertEquals(1080, BackdropGeometry.topArtworkHeight(2400, 1080));
    }

    @Test
    public void invalidRootSizeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BackdropGeometry.topArtworkHeight(0, 1080));
    }
}
