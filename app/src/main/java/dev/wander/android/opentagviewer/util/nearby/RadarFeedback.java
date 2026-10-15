package dev.wander.android.opentagviewer.util.nearby;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * How a radar screen turns a distance estimate into sound and touch.
 *
 * <p><b>Pure arithmetic, no Android</b> - the mapping is the feature's whole contract with the
 * user ("faster means nearer"), and it is the kind of curve somebody tunes once and then wants
 * a test to pin down, so it lives where a JVM test can reach it without a radio.
 *
 * <p>The interval is linear in metres rather than in RSSI on purpose: the screen already
 * thinks in metres through {@link ProximityDistance}, and a linear metres curve keeps the
 * fastest zone - the last two metres, which is where a person is actually hunting - spread
 * across most of the beep range rather than compressed into a corner of a logarithmic one.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RadarFeedback {

    /** Fastest the beeps get, right at the tag. Faster reads as a glitch, not as "here". */
    static final long MIN_INTERVAL_MS = 120;

    /**
     * Slowest the beeps get, at {@link #RANGE_TOP_M} and beyond. Slower and the gaps stop
     * reading as a rhythm at all - the screen looks dead while it is still tracking.
     */
    static final long MAX_INTERVAL_MS = 2_400;

    /** The distance at which the interval stops growing; past this everything sounds "far". */
    public static final double RANGE_TOP_M = 10.0;

    /** Under this distance the phone also vibrates on each beep - "in your hand" range. */
    static final double VIBRATE_UNDER_M = 1.0;

    /**
     * Milliseconds between beeps for a device this far away, linear from {@link #MIN_INTERVAL_MS}
     * at zero metres to {@link #MAX_INTERVAL_MS} at {@link #RANGE_TOP_M}, clamped at both ends.
     */
    public static long beepIntervalMs(final double meters) {
        if (Double.isNaN(meters)) {
            // Unknown distance must sound like "far", not "on top of it" - the clamp alone
            // would send NaN through as the minimum interval.
            return MAX_INTERVAL_MS;
        }
        final double clamped = Math.min(Math.max(meters, 0.0), RANGE_TOP_M);
        final long interval =
                MIN_INTERVAL_MS + (long) (clamped / RANGE_TOP_M * (MAX_INTERVAL_MS - MIN_INTERVAL_MS));
        return Math.min(interval, MAX_INTERVAL_MS);
    }

    /** Whether this distance is close enough that vibration adds to the sound. */
    public static boolean shouldVibrate(final double meters) {
        return meters < VIBRATE_UNDER_M;
    }
}
