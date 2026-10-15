package dev.wander.android.opentagviewer.util.nearby;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rhythm contract of {@link RadarFeedback}: faster means nearer, clamped at both ends.
 */
public class RadarFeedbackTest {

    @Test
    public void beepsFasterWhenTheDeviceIsNearer() {
        assertTrue(RadarFeedback.beepIntervalMs(0.5) < RadarFeedback.beepIntervalMs(2.0));
        assertTrue(RadarFeedback.beepIntervalMs(2.0) < RadarFeedback.beepIntervalMs(9.0));
    }

    @Test
    public void clampsTheIntervalAtBothEnds() {
        // Touching the tag must not produce a buzz the ear cannot parse, and a far tag must
        // not pause so long the screen reads as dead.
        assertEquals(RadarFeedback.MIN_INTERVAL_MS, RadarFeedback.beepIntervalMs(0.0));
        assertEquals(RadarFeedback.MIN_INTERVAL_MS, RadarFeedback.beepIntervalMs(-1.0));
        assertEquals(RadarFeedback.MAX_INTERVAL_MS, RadarFeedback.beepIntervalMs(500.0));
    }

    @Test
    public void saturatesPastTheRangeTop() {
        assertEquals(RadarFeedback.MAX_INTERVAL_MS,
                RadarFeedback.beepIntervalMs(RadarFeedback.RANGE_TOP_M + 1));
    }

    @Test
    public void vibratesOnlyUnderOneMetre() {
        assertTrue(RadarFeedback.shouldVibrate(0.5));
        assertFalse(RadarFeedback.shouldVibrate(1.0));
        assertFalse(RadarFeedback.shouldVibrate(5.0));
    }

    @Test
    public void unknownDistanceSoundsFarNotNear() {
        // NaN through the clamps lands at zero interval - the fastest beep - unless caught.
        // "No reading" must not scream "on top of it".
        assertEquals(RadarFeedback.MAX_INTERVAL_MS, RadarFeedback.beepIntervalMs(Double.NaN));
    }
}
