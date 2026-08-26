package local.n3view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DisplayGeometryTest {
    @Test
    public void fitPreservesWideVideoInsideContainer() {
        assertEquals(new DisplayGeometry.Size(1920, 1080),
                DisplayGeometry.calculate(2400, 1080, 1920, 1080, DisplayGeometry.Mode.FIT));
    }

    @Test
    public void fillPreservesWideVideoAndCropsOverflow() {
        assertEquals(new DisplayGeometry.Size(2400, 1350),
                DisplayGeometry.calculate(2400, 1080, 1920, 1080, DisplayGeometry.Mode.FILL));
    }

    @Test
    public void stretchUsesEntireContainer() {
        assertEquals(new DisplayGeometry.Size(2400, 1080),
                DisplayGeometry.calculate(2400, 1080, 1920, 1080, DisplayGeometry.Mode.STRETCH));
    }

    @Test
    public void fitWorksInPortrait() {
        assertEquals(new DisplayGeometry.Size(1080, 608),
                DisplayGeometry.calculate(1080, 2400, 1920, 1080, DisplayGeometry.Mode.FIT));
    }

    @Test
    public void missingSourceDimensionsFallBackToContainer() {
        assertEquals(new DisplayGeometry.Size(2400, 1080),
                DisplayGeometry.calculate(2400, 1080, 0, 0, DisplayGeometry.Mode.FIT));
    }

    @Test
    public void modeCycleReturnsToFit() {
        assertEquals(DisplayGeometry.Mode.FILL, DisplayGeometry.Mode.FIT.next());
        assertEquals(DisplayGeometry.Mode.STRETCH, DisplayGeometry.Mode.FILL.next());
        assertEquals(DisplayGeometry.Mode.FIT, DisplayGeometry.Mode.STRETCH.next());
    }
}
