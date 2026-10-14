package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.wander.android.opentagviewer.ble.NearbyPresenceTracker.Event;
import dev.wander.android.opentagviewer.ble.NearbyPresenceTracker.Transition;

import java.util.List;

/**
 * The edge contract of {@link NearbyPresenceTracker}: appear once, disappear once after the
 * silence, and nothing in between.
 */
public class NearbyPresenceTrackerTest {

    private static final long GONE = NearbyPresenceTracker.GONE_AFTER_MS;

    @Test
    public void reportsAnArrivalOnceNotPerFrame() {
        final NearbyPresenceTracker tracker = new NearbyPresenceTracker();

        assertEquals(1, tracker.hear("aabbccddeeff", 1_000).size());
        // Frames keep arriving every few seconds: none may produce a second event.
        assertTrue(tracker.hear("aabbccddeeff", 2_000).isEmpty());
        assertTrue(tracker.hear("aabbccddeeff", 60_000).isEmpty());
    }

    @Test
    public void reportsADepartureOnlyAfterTheSilence() {
        final NearbyPresenceTracker tracker = new NearbyPresenceTracker();
        tracker.hear("aabbccddeeff", 1_000);

        assertTrue(tracker.sweep(1_000 + GONE).isEmpty());

        final List<Transition> transitions = tracker.sweep(1_000 + GONE + 1);
        assertEquals(1, transitions.size());
        assertEquals(Event.DISAPPEARED, transitions.get(0).getEvent());
        assertEquals("aabbccddeeff", transitions.get(0).getKeyPrefix());
    }

    @Test
    public void survivesAStutterInsideTheSilence() {
        final NearbyPresenceTracker tracker = new NearbyPresenceTracker();
        tracker.hear("aabbccddeeff", 1_000);
        tracker.hear("aabbccddeeff", 60_000); // a late frame restarts the wait

        assertTrue(tracker.sweep(1_000 + GONE + 1).isEmpty());
        assertTrue(tracker.sweep(60_000 + GONE).isEmpty());
        assertEquals(1, tracker.sweep(60_000 + GONE + 1).size());
    }

    @Test
    public void treatsAReturnAsANewArrival() {
        final NearbyPresenceTracker tracker = new NearbyPresenceTracker();
        tracker.hear("aabbccddeeff", 1_000);
        tracker.sweep(1_000 + GONE + 1);

        final List<Transition> back = tracker.hear("aabbccddeeff", 200_000);
        assertEquals(1, back.size());
        assertEquals(Event.APPEARED, back.get(0).getEvent());
        assertTrue(tracker.isPresent("aabbccddeeff"));
    }

    @Test
    public void forgetsARetiredPrefixEntirely() {
        final NearbyPresenceTracker tracker = new NearbyPresenceTracker();
        tracker.hear("aabbccddeeff", 1_000);
        tracker.forget("aabbccddeeff");

        // No departure may surface for a prefix that was deliberately dropped.
        assertTrue(tracker.sweep(1_000 + GONE + 1).isEmpty());
        assertFalse(tracker.isPresent("aabbccddeeff"));
    }
}
