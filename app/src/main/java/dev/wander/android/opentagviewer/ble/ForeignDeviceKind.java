package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

/**
 * A coarse guess at what kind of hardware sits behind a Find My broadcast.
 *
 * <p><b>The primary evidence is what the device declares.</b> Bits 5-4 of the Find My status
 * byte encode the broadcaster's own class - the same decode FindMy.py's scanner does, and it
 * is the honest signal: the device says what it is, rather than us guessing from the outside.
 * It claims a class, never a model - "an Apple device" does not say phone or laptop.
 *
 * <p><b>The secondary evidence covers records that predate the status field.</b> The
 * {@code otherAppleTypeMask} records which non-Find-My Apple TLVs ever rode in the same
 * manufacturer blob: a host pads its advertisement with Nearby Info, Handoff and friends,
 * while a dedicated accessory's blob is the bare beacon. One padded frame is proof of host;
 * a bare frame alone proves nothing.
 *
 * <p>What is not in here, on purpose: RSSI says where the device is, not what it is, and the
 * key prefix rotates by design. Because the class is declared in the frame rather than the
 * key, it survives rotation - which is what makes it usable both as a row label and as a
 * rotation-suggestion guard.
 */
public enum ForeignDeviceKind {
    /** An iPhone, iPad, Watch or Mac - the status byte's {@code 0b00}, or host-shaped blobs. */
    APPLE_DEVICE,
    /** Apple's own tracker, status {@code 0b01}. */
    AIRTAG,
    /** A licensed third-party Find My accessory - a Chipolo and friends, status {@code 0b10}. */
    THIRD_PARTY,
    /** Status {@code 0b11}. */
    AIRPODS,
    /** Nothing recorded that declares a class. */
    UNKNOWN;

    /**
     * The class the accumulated evidence supports.
     *
     * @param statusByte the freshest raw status byte, or null on records that predate it
     * @param otherAppleTypeMask bitmask of non-Find-My Apple TLVs ever heard from this prefix
     */
    public static ForeignDeviceKind of(
            @Nullable final Integer statusByte,
            final long otherAppleTypeMask) {
        if (statusByte != null) {
            switch ((statusByte >> 4) & 0b11) {
                case 0b00: return APPLE_DEVICE;
                case 0b01: return AIRTAG;
                case 0b10: return THIRD_PARTY;
                case 0b11: return AIRPODS;
                default: return UNKNOWN;
            }
        }
        return otherAppleTypeMask != 0 ? APPLE_DEVICE : UNKNOWN;
    }

    /**
     * Whether two kinds could be the same physical device - a rotation suggestion has no
     * business pairing an AirTag-shaped old key with an AirPods-shaped new one. UNKNOWN is
     * compatible with everything: absent evidence cannot contradict.
     */
    public boolean isCompatibleWith(final ForeignDeviceKind other) {
        return this == UNKNOWN || other == UNKNOWN || this == other;
    }
}
