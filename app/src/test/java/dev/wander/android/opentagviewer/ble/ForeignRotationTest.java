package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.State;

/**
 * {@link ForeignRotation#pickConfidentPredecessor}: the unconfirmed merge may only
 * pair a just-born prefix with a just-died one at a matching signal strength, and
 * must refuse the pairings that only look similar on the clock.
 */
public class ForeignRotationTest {

    private static final long NOW = 1_000_000;

    @Rule
    public final TemporaryFolder filesDir = new TemporaryFolder();

    private static NearbyDeviceSighting sighting(
            final String prefix, final int rssi, final long seenAtMs) {
        return NearbyDeviceSighting.builder()
                .mac("AA:BB:CC:DD:EE:FF")
                .keyPrefix(prefix)
                .rssi(rssi)
                .batteryLevel(BatteryLevel.MEDIUM)
                .statusByte(0x10)
                .state(State.OWNER_NEARBY)
                .seenAtMs(seenAtMs)
                .build();
    }

    @Test
    public void mergesThePairThatLooksLikeARotation() {
        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        // The predecessor was last heard 25s ago, the successor first heard 5s later.
        log.hear(sighting("aabbccddeeff", -60, NOW - 25_000));
        log.hear(sighting("112233445566", -55, NOW - 20_000));

        assertEquals("aabbccddeeff", ForeignRotation.pickConfidentPredecessor(
                "112233445566", log, NOW));
    }

    @Test
    public void refusesWhileThePredecessorIsNotQuietYet() {
        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        // Still "speaking" 10s ago - under QUIET_FOR_MS this is a tag standing next
        // to another tag, not a rotation.
        log.hear(sighting("aabbccddeeff", -60, NOW - 10_000));
        log.hear(sighting("112233445566", -60, NOW - 5_000));

        assertNull(ForeignRotation.pickConfidentPredecessor("112233445566", log, NOW));
    }

    @Test
    public void refusesOnAMismatchedSignalStrength() {
        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        log.hear(sighting("aabbccddeeff", -60, NOW - 25_000));
        // A rotating tag does not move; -60 to -90 in seconds is a different device.
        log.hear(sighting("112233445566", -90, NOW - 20_000));

        assertNull(ForeignRotation.pickConfidentPredecessor("112233445566", log, NOW));
    }

    @Test
    public void refusesALooseTimingMatch() {
        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        log.hear(sighting("aabbccddeeff", -60, NOW - 60_000));
        // Inside MAX_MERGE_GAP so the banner may ask, but beyond CONFIDENT_GAP_MS -
        // over half a minute apart, no merge without a confirmation.
        log.hear(sighting("112233445566", -60, NOW - 25_000));

        assertNull(ForeignRotation.pickConfidentPredecessor("112233445566", log, NOW));
    }

    @Test
    public void refusesAPrefixWhoseRotationMomentIsPast() {
        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        log.hear(sighting("aabbccddeeff", -60, NOW - 150_000));
        // First heard three minutes ago: its stored RSSI has been drifting ever
        // since, and a "match" against it says nothing about the transition.
        log.hear(sighting("112233445566", -60, NOW - 149_000));

        assertNull(ForeignRotation.pickConfidentPredecessor("112233445566", log, NOW));
    }

    @Test
    public void neverLinksIntoAnAlreadyRotatedPair() {
        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        log.hear(sighting("aabbccddeeff", -60, NOW - 50_000));
        log.hear(sighting("66778899aabb", -62, NOW - 45_000));
        log.markRotated("aabbccddeeff", "66778899aabb");
        // The chain continues: the successor rotated again - the fresh prefix may
        // claim the successor, but never re-claim the linked predecessor.
        log.hear(sighting("112233445566", -63, NOW - 20_000));

        assertEquals("66778899aabb", ForeignRotation.pickConfidentPredecessor(
                "112233445566", log, NOW));
        // And a dead prefix hearing again late cannot absorb anything.
        assertNull(ForeignRotation.pickConfidentPredecessor("aabbccddeeff", log, NOW));
    }
}
