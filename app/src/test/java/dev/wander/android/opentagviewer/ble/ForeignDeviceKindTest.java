package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The evidence-to-class rules of {@link ForeignDeviceKind}: the status byte's declared type
 * decides when it is recorded, host-shaped blobs cover records that predate it, and anything
 * else stays unclaimed.
 */
public class ForeignDeviceKindTest {

    @Test
    public void theDeclaredTypeDecides() {
        // Bits 5-4 of the status byte, as FindMy.py's scanner decodes them.
        assertEquals(ForeignDeviceKind.APPLE_DEVICE, ForeignDeviceKind.of(0x00, 0));
        assertEquals(ForeignDeviceKind.AIRTAG, ForeignDeviceKind.of(0x10, 0));
        assertEquals(ForeignDeviceKind.THIRD_PARTY, ForeignDeviceKind.of(0x20, 0));
        assertEquals(ForeignDeviceKind.AIRPODS, ForeignDeviceKind.of(0x30, 0));
        // The battery bits above do not confuse the decode.
        assertEquals(ForeignDeviceKind.AIRTAG, ForeignDeviceKind.of(0x90, 0));
        // Neither does the blob's padding - the declaration wins over corroboration.
        assertEquals(ForeignDeviceKind.AIRTAG, ForeignDeviceKind.of(0x10, 1L << 0x10));
    }

    @Test
    public void hostShapedBlobsStandInForAMissingStatus() {
        // Records written before the status field existed still yield a class when a padded
        // frame proved the sender was a host.
        assertEquals(ForeignDeviceKind.APPLE_DEVICE,
                ForeignDeviceKind.of(null, 1L << 0x10));
    }

    @Test
    public void nothingRecordedSaysNothing() {
        assertEquals(ForeignDeviceKind.UNKNOWN, ForeignDeviceKind.of(null, 0));
    }

    @Test
    public void unknownIsCompatibleWithEverything() {
        assertTrue(ForeignDeviceKind.UNKNOWN.isCompatibleWith(ForeignDeviceKind.AIRTAG));
        assertTrue(ForeignDeviceKind.AIRTAG.isCompatibleWith(ForeignDeviceKind.UNKNOWN));
        assertTrue(ForeignDeviceKind.AIRTAG.isCompatibleWith(ForeignDeviceKind.AIRTAG));
    }

    @Test
    public void anAirTagCannotRotateIntoAirPods() {
        assertFalse(ForeignDeviceKind.AIRTAG.isCompatibleWith(ForeignDeviceKind.AIRPODS));
        assertFalse(ForeignDeviceKind.APPLE_DEVICE.isCompatibleWith(ForeignDeviceKind.AIRTAG));
        assertFalse(ForeignDeviceKind.THIRD_PARTY.isCompatibleWith(ForeignDeviceKind.AIRTAG));
    }
}
