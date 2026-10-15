package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.State;

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
}
