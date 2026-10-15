package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The merge rule of {@link NearbyRotationHeuristic}: quiet old prefix, promptly-arrived new
 * one, or it is not a rotation.
 */
public class NearbyRotationHeuristicTest {

    @Test
    public void acceptsAPrefixThatArrivedAsTheNamedOneWentQuiet() {
        // Named last heard at t=0, gone quiet since, unnamed first heard 5s later - the
        // textbook rotation shape.
        assertTrue(NearbyRotationHeuristic.couldBeRotation(0L, 5_000L, 30_000L));
    }

    @Test
    public void refusesWhileTheNamedPrefixIsStillPresent() {
        // A tag still advertising cannot also be the new one - absorbing a present neighbour
        // into a coincidence is the failure this bound exists for.
        assertFalse(NearbyRotationHeuristic.couldBeRotation(0L, 5_000L, 10_000L));
    }

    @Test
    public void refusesANewPrefixThatArrivedTooLate() {
        // Minutes after the departure is a passer-by, not a rotation.
        assertFalse(NearbyRotationHeuristic.couldBeRotation(
                0L, NearbyRotationHeuristic.MAX_MERGE_GAP_MS + 1, 200_000L));
    }

    @Test
    public void refusesANameForAPrefixThatPredatesIt() {
        // The unnamed one was heard *before* the named one's last frame - overlapping
        // histories, not a handover.
        assertFalse(NearbyRotationHeuristic.couldBeRotation(10_000L, 5_000L, 100_000L));
    }

    @Test
    public void acceptsTheGapAtExactlyTheBound() {
        assertTrue(NearbyRotationHeuristic.couldBeRotation(
                0L, NearbyRotationHeuristic.MAX_MERGE_GAP_MS,
                NearbyRotationHeuristic.MAX_MERGE_GAP_MS + NearbyRotationHeuristic.QUIET_FOR_MS));
    }

    @Test
    public void confidentMergeNeedsSecondsAndAMatchingSignal() {
        // Old prefix quiet for 25s, new one appeared 5s after its last frame at nearly the
        // same strength - the in-place rotation the auto-merge exists for.
        assertTrue(NearbyRotationHeuristic.isConfidentRotation(
                0L, -60, 5_000L, -63, 25_000L));
    }

    @Test
    public void confidentMergeRefusesAWiderGap() {
        // Loose-rule timing but 40s apart - could still be a rotation, just not one to act
        // on without asking.
        assertFalse(NearbyRotationHeuristic.isConfidentRotation(
                0L, -60, 40_000L, -60, 50_000L));
    }

    @Test
    public void confidentMergeRefusesAStrongerNewcomer() {
        // Close in time but 20dB apart - a nearer stranger, not the same tag in place.
        assertFalse(NearbyRotationHeuristic.isConfidentRotation(
                0L, -60, 5_000L, -40, 25_000L));
    }
}
