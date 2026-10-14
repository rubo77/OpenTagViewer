package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Whether a newly appeared prefix might be a named tag that rotated its advertisement key.
 *
 * <p><b>The rule the suggestion banner encodes.</b> A Find My tag rolls its key roughly every
 * fifteen minutes; the old prefix then goes silent for good and the tag keeps advertising
 * under a new one within seconds. So a merge is plausible when two things hold at once:
 *
 * <ul>
 *   <li>the <b>named</b> prefix has actually gone quiet - at least {@link #QUIET_FOR_MS} of
 *       silence, so an ordinary still-present tag cannot be absorbed into a coincidence;</li>
 *   <li>the <b>unnamed</b> prefix first appeared within {@link #MAX_MERGE_GAP_MS} of the named
 *       one's last hearing - a rotation is near-instant, so a tag that shows up minutes after
 *       the departure is somebody else arriving.</li>
 * </ul>
 *
 * <p>Both bounds are deliberately generous toward missed frames, and neither is proof - which
 * is why the result drives a <i>suggestion the user confirms</i>, not a merge.
 *
 * <p>Pure arithmetic over three timestamps so the whole rule sits under a JVM test.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NearbyRotationHeuristic {

    /**
     * How silent the named prefix must be before its absence means anything.
     *
     * <p>Chosen just under {@link NearbyDeviceBook#GONE_AFTER_MS}: the book drops a quiet row
     * at 30s, and a rotation that "did not happen" would still show the old row the whole
     * time - so the check asks for near-silence but not full departure.
     */
    static final long QUIET_FOR_MS = 20_000;

    /**
     * How long after the named prefix's last hearing the unnamed one may first appear and
     * still be the rotation rather than a passer-by. Two minutes covers a few missed frames
     * plus Bluetooth's advertising jitter without stretching into "somebody walked in".
     */
    static final long MAX_MERGE_GAP_MS = 120_000;

    /**
     * Whether these two timestamps look like one tag changing keys.
     *
     * @param namedLastSeenMs   when the named prefix was last heard
     * @param unnamedFirstSeenMs when the unnamed prefix was first heard
     * @param nowMs             the caller's clock, injected for the test
     */
    public static boolean couldBeRotation(
            final long namedLastSeenMs, final long unnamedFirstSeenMs, final long nowMs) {
        if (nowMs - namedLastSeenMs < QUIET_FOR_MS) {
            return false;
        }
        final long gap = unnamedFirstSeenMs - namedLastSeenMs;
        return gap >= 0 && gap <= MAX_MERGE_GAP_MS;
    }
}
