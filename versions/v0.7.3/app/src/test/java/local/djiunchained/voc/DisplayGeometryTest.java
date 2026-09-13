package local.djiunchained.voc;

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

    @Test
    public void sourceAspectCyclesThroughWideAndClassic() {
        assertEquals(DisplayGeometry.SourceAspect.WIDE_16_9,
                DisplayGeometry.SourceAspect.AUTO.next());
        assertEquals(DisplayGeometry.SourceAspect.CLASSIC_4_3,
                DisplayGeometry.SourceAspect.WIDE_16_9.next());
        assertEquals(DisplayGeometry.SourceAspect.AUTO,
                DisplayGeometry.SourceAspect.CLASSIC_4_3.next());
    }

    @Test
    public void forcedSourceAspectsUseExactRatios() {
        assertEquals(new DisplayGeometry.Size(16, 9),
                DisplayGeometry.sourceSize(1920, 1080,
                        DisplayGeometry.SourceAspect.WIDE_16_9));
        assertEquals(new DisplayGeometry.Size(4, 3),
                DisplayGeometry.sourceSize(1920, 1080,
                        DisplayGeometry.SourceAspect.CLASSIC_4_3));
    }

    @Test
    public void shortsViewportBlocksEverythingOutsideNineBySixteen() {
        assertEquals(new DisplayGeometry.Size(608, 1080),
                DisplayGeometry.shortsViewport(2400, 1080));
        assertEquals(new DisplayGeometry.Size(1080, 1920),
                DisplayGeometry.shortsViewport(1080, 2400));
    }
}
