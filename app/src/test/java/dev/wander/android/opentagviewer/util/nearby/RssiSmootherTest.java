package dev.wander.android.opentagviewer.util.nearby;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The window and median rules of {@link RssiSmoother}: old readings leave, outliers lose.
 */
public class RssiSmootherTest {

    @Test
    public void answersNanWhileNothingWasHeard() {
        assertTrue(Double.isNaN(new RssiSmoother(4_000).median()));
    }

    @Test
    public void aSingleReadingIsItsOwnMedian() {
        final RssiSmoother smoother = new RssiSmoother(4_000);
        smoother.add(-70, 1_000);

        assertEquals(-70.0, smoother.median(), 0.001);
    }

    @Test
    public void evictsReadingsOlderThanTheWindow() {
        final RssiSmoother smoother = new RssiSmoother(4_000);
        smoother.add(-80, 1_000);
        smoother.add(-50, 6_000); // the -80 is now 5s old, outside a 4s window

        assertEquals(1, smoother.size());
        assertEquals(-50.0, smoother.median(), 0.001);
    }

    @Test
    public void theMedianShrugsOffASingleStrongReflection() {
        final RssiSmoother smoother = new RssiSmoother(10_000);
        // Typical sequence hunting indoors: a bounce spikes far stronger than the truth.
        smoother.add(-75, 1_000);
        smoother.add(-72, 2_000);
        smoother.add(-35, 3_000); // the multipath spike
        smoother.add(-74, 4_000);
        smoother.add(-73, 5_000);

        // A mean would land around -66 and claim the tag is much nearer; the median
        // answers what the tag reads most of the time.
        assertEquals(-73.0, smoother.median(), 0.001);
    }

    @Test
    public void takesTheLowerMiddleOnAnEvenCount() {
        final RssiSmoother smoother = new RssiSmoother(10_000);
        smoother.add(-80, 1_000);
        smoother.add(-60, 2_000);

        // Lower-middle, not the midpoint: erring toward "further away" is the safe direction.
        assertEquals(-80.0, smoother.median(), 0.001);
    }

    @Test
    public void aZeroWindowAnswersOnlyTheNewestReading() {
        final RssiSmoother smoother = new RssiSmoother(0);
        smoother.add(-80, 1_000);
        smoother.add(-60, 2_000);

        assertEquals(-60.0, smoother.median(), 0.001);
        assertEquals(1, smoother.size());
    }

    @Test
    public void shrinkingTheWindowEvictsImmediately() {
        final RssiSmoother smoother = new RssiSmoother(10_000);
        smoother.add(-80, 1_000);
        smoother.add(-60, 8_000);

        smoother.setWindowMs(2_000);

        // The -80 at t=1000 is 7s older than the newest reading - outside a 2s window already,
        // without waiting for a new frame to evict it.
        assertEquals(-60.0, smoother.median(), 0.001);
        assertEquals(1, smoother.size());
    }
}
