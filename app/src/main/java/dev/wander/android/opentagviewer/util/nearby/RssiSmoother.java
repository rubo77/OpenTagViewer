package dev.wander.android.opentagviewer.util.nearby;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A sliding-window median over raw RSSI readings.
 *
 * <p><b>Why a median, not a mean.</b> BLE signal strength at 2.4 GHz is not Gaussian noise
 * around a truth - it is multipath: the same tag at the same distance reads 15 dB apart
 * depending on how the wave bounced off walls and the holder's body, and a single strong
 * reflection is an *outlier*, not part of a spread to average. A mean lets one lucky bounce
 * claim the tag is twice as close; a median answers "what it reads most of the time", which
 * is the honest estimate for a person hunting by sound.
 *
 * <p>The window is wall-clock, not frame-count: advertising is periodic, so a number of
 * seconds means a similar number of readings however crowded the air is - and it degrades
 * gracefully. Sparse readings simply produce a median over fewer points rather than a stale
 * average dragged along forever.
 *
 * <p>Pure - no Android. Window and timestamps are injected so the whole eviction and median
 * behaviour sits under a JVM test.
 */
public final class RssiSmoother {

    /** One reading: when it was heard and how strong it was. */
    private static final class Reading {
        final long atMs;
        final int rssi;

        Reading(final long atMs, final int rssi) {
            this.atMs = atMs;
            this.rssi = rssi;
        }
    }

    private final Deque<Reading> readings = new ArrayDeque<>();
    private long windowMs;

    public RssiSmoother(final long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * How far back readings count, in milliseconds. Zero turns smoothing off: the estimate
     * then answers the newest raw reading, which is the honest way to offer "no smoothing"
     * rather than a different code path.
     *
     * <p>Shrinking evicts immediately, measured against the newest reading rather than the
     * next one to arrive - otherwise a widened-then-shrunk window keeps answering with data
     * the user just told the window to forget.
     */
    public void setWindowMs(final long windowMs) {
        this.windowMs = Math.max(0, windowMs);
        if (!this.readings.isEmpty()) {
            evictOlderThan(this.readings.peekLast().atMs);
        }
    }

    /** Records a reading and evicts whatever fell out of the window. */
    public void add(final int rssi, final long nowMs) {
        this.readings.addLast(new Reading(nowMs, rssi));
        evictOlderThan(nowMs);
    }

    private void evictOlderThan(final long nowMs) {
        while (!this.readings.isEmpty()
                && nowMs - this.readings.peekFirst().atMs > this.windowMs) {
            this.readings.pollFirst();
        }
    }

    /**
     * The median RSSI in the window, or {@link Double#NaN} while nothing is in it.
     *
     * <p>Even counts take the lower-middle value rather than the interpolated midpoint -
     * for RSSI that errs toward the weaker (further) reading, which is the safe direction
     * for a hunting aid to err in.
     */
    public double median() {
        if (this.readings.isEmpty()) {
            return Double.NaN;
        }
        final List<Integer> values = new ArrayList<>(this.readings.size());
        for (final Reading r : this.readings) {
            values.add(r.rssi);
        }
        Collections.sort(values);
        return values.get((values.size() - 1) / 2);
    }

    /** How many readings the window currently holds. */
    public int size() {
        return this.readings.size();
    }
}
