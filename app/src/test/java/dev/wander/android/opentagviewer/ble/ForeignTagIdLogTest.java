package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.State;
import dev.wander.android.opentagviewer.util.android.PhoneLocation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * {@link ForeignTagIdLog} persistence: a reloaded entry must carry enough of the last frame
 * to rebuild an honest offline row for a named device that is not currently audible.
 */
public class ForeignTagIdLogTest {

    @Rule
    public final TemporaryFolder filesDir = new TemporaryFolder();

    private static NearbyDeviceSighting sighting(final String prefix) {
        return NearbyDeviceSighting.builder()
                .mac("AA:BB:CC:DD:EE:FF")
                .keyPrefix(prefix)
                .rssi(-67)
                .batteryLevel(BatteryLevel.MEDIUM)
                .statusByte(0x10)
                .state(State.OWNER_NEARBY)
                .seenAtMs(1_000)
                .build();
    }

    @Test
    public void reloadsTheLastFramesFactsForOfflineRows() {
        final ForeignTagIdLog writer = new ForeignTagIdLog(this.filesDir.getRoot());
        writer.hear(sighting("aabbccddeeff"));
        writer.flush();

        final ForeignTagIdLog reader = new ForeignTagIdLog(this.filesDir.getRoot());
        final NearbyDeviceSighting rebuilt =
                reader.entry("aabbccddeeff").toSighting("aabbccddeeff");

        assertEquals("AA:BB:CC:DD:EE:FF", rebuilt.getMac());
        assertEquals(-67, rebuilt.getRssi());
        assertEquals(BatteryLevel.MEDIUM, rebuilt.getBatteryLevel());
        assertEquals(State.OWNER_NEARBY, rebuilt.getState());
        assertEquals(1_000, rebuilt.getSeenAtMs());
    }

    @Test
    public void toleratesRecordsWrittenBeforeTheFrameFieldsExisted() throws Exception {
        // A log written by an older app version knows nothing of mac/battery/state - it must
        // still load and rebuild a row with explicit nulls rather than invented values.
        final File file = new File(this.filesDir.getRoot(), ForeignTagIdLog.FILE_NAME);
        Files.write(file.toPath(),
                ("{\"aabbccddeeff\":{\"first\":1000,\"last\":2000,\"rssi\":-80}}")
                        .getBytes(StandardCharsets.UTF_8));

        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        final NearbyDeviceSighting rebuilt =
                log.entry("aabbccddeeff").toSighting("aabbccddeeff");

        assertEquals(2_000, rebuilt.getSeenAtMs());
        assertNull(rebuilt.getMac());
        assertNull(rebuilt.getBatteryLevel());
        assertNull(rebuilt.getState());
    }

    @Test
    public void reloadsTheStampedPosition() {
        final ForeignTagIdLog writer = new ForeignTagIdLog(this.filesDir.getRoot());
        writer.hear(sighting("aabbccddeeff"), new PhoneLocation.Fix(52.52, 13.405, 18));
        writer.flush();

        final ForeignTagIdLog reader = new ForeignTagIdLog(this.filesDir.getRoot());
        final ForeignTagIdLog.Entry entry = reader.entry("aabbccddeeff");

        assertEquals(Boolean.TRUE, entry.hasPosition());
        assertEquals(Double.valueOf(52.52), entry.getLastLatitude());
        assertEquals(Double.valueOf(13.405), entry.getLastLongitude());
        assertEquals(Long.valueOf(18), entry.getLastAccuracyMetres());
        assertEquals(Long.valueOf(1_000), entry.getLastPositionMs());
    }

    @Test
    public void aHearingWithoutFixKeepsTheLastKnownPosition() {
        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        log.hear(sighting("aabbccddeeff"), new PhoneLocation.Fix(52.52, 13.405, 18));
        log.hear(NearbyDeviceSighting.builder()
                .mac("AA:BB:CC:DD:EE:FF")
                .keyPrefix("aabbccddeeff")
                .rssi(-70)
                .seenAtMs(2_000)
                .build());

        assertEquals(Double.valueOf(52.52),
                log.entry("aabbccddeeff").getLastLatitude());
        assertEquals(Long.valueOf(1_000),
                log.entry("aabbccddeeff").getLastPositionMs());
    }

    @Test
    public void rotationHandsThePositionToTheSuccessor() {
        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        log.hear(sighting("aabbccddeeff"), new PhoneLocation.Fix(52.52, 13.405, 18));
        log.hear(sighting("ffee00112233"));

        log.markRotated("aabbccddeeff", "ffee00112233");

        final ForeignTagIdLog.Entry successor = log.entry("ffee00112233");
        assertEquals(Double.valueOf(52.52), successor.getLastLatitude());
        assertEquals(Long.valueOf(1_000), successor.getLastPositionMs());
        assertEquals("ffee00112233", log.entry("aabbccddeeff").getRotatedInto());
    }

    @Test
    public void rotationKeepsASuccessorsOwnPosition() {
        final ForeignTagIdLog log = new ForeignTagIdLog(this.filesDir.getRoot());
        log.hear(sighting("aabbccddeeff"), new PhoneLocation.Fix(52.52, 13.405, 18));
        log.hear(sighting("ffee00112233"), new PhoneLocation.Fix(48.14, 11.58, 30));

        log.markRotated("aabbccddeeff", "ffee00112233");

        assertEquals(Double.valueOf(48.14),
                log.entry("ffee00112233").getLastLatitude());
    }

    @Test
    public void removeDropsTheEntryAndPersistsTheDeletion() {
        final ForeignTagIdLog writer = new ForeignTagIdLog(this.filesDir.getRoot());
        writer.hear(sighting("aabbccddeeff"));
        writer.hear(sighting("111111111111"));

        writer.remove("aabbccddeeff");
        writer.flush();

        final ForeignTagIdLog reader = new ForeignTagIdLog(this.filesDir.getRoot());
        assertNull(reader.entry("aabbccddeeff"));
        assertEquals(1, reader.entries().size());
        assertEquals("111111111111", reader.entries().keySet().iterator().next());
    }

    @Test
    public void remembersARejectedPairAcrossRestarts() {
        final ForeignTagIdLog writer = new ForeignTagIdLog(this.filesDir.getRoot());
        writer.hear(sighting("aabbccddeeff"));
        writer.hear(sighting("111111111111"));

        writer.rejectRotation("111111111111", "aabbccddeeff");
        writer.flush();

        final ForeignTagIdLog reader = new ForeignTagIdLog(this.filesDir.getRoot());
        assertTrue(reader.entry("111111111111").hasRejectedPredecessor("aabbccddeeff"));
        // The rejection binds the pair, not the prefix: another departed name may still ask.
        assertFalse(reader.entry("111111111111").hasRejectedPredecessor("222222222222"));
        assertFalse(reader.entry("aabbccddeeff").hasRejectedPredecessor("111111111111"));
    }
}
