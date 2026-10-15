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
     * The tight timing bound for an unconfirmed merge. A rotation takes seconds - the tag
     * swaps keys in place - so a pair this close in time is already rare.
     */
    static final long CONFIDENT_GAP_MS = 30_000;

    /**
     * How similar the signal strengths on either side of a confident merge must be. The tag
     * does not move while it rotates, so its RSSI barely does either; a stranger arriving in
     * the same half minute at a matching strength is the rare case this bound exists for.
     */
    static final int CONFIDENT_RSSI_DIFF_DB = 12;

    /**
     * How long after the named prefix's last hearing a new prefix may appear and still be
     * worth asking about. A phone that beacons only in bursts leaves hours between windows,
     * so the suggestion tier accepts what the merge tier never could - the question costs a
     * tap and a wrong "no" is remembered, while a wrong auto-merge marries strangers.
     */
    static final long SUGGEST_GAP_MS = 4 * 3_600_000;

    /**
     * The signal-strength slack a suggestion tolerates. Looser than the confident bound
     * because hours of drift sit between the two hearings; tighter would miss the phone
     * that moved from the desk to a pocket.
     */
    static final int SUGGEST_RSSI_DIFF_DB = 15;

    /**
     * How far apart the two stamped positions may be before the suggestion is off the table.
     * A rotation happens where the tag stands; a candidate heard half a kilometre away is
     * somebody else's device that happened to surface near the gap.
     */
    static final double SUGGEST_MAX_DISTANCE_M = 500;

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

    /**
     * The same question with the burden of proof a merge without confirmation needs: the
     * timing window narrows to seconds, and the signal strength has to stay in range - a
     * rotating tag does not move, so a pair that fails this is likelier two devices.
     */
    public static boolean isConfidentRotation(
            final long oldLastSeenMs, final int oldRssi,
            final long newFirstSeenMs, final int newRssi, final long nowMs) {
        if (!couldBeRotation(oldLastSeenMs, newFirstSeenMs, nowMs)) {
            return false;
        }
        if (newFirstSeenMs - oldLastSeenMs > CONFIDENT_GAP_MS) {
            return false;
        }
        return Math.abs(newRssi - oldRssi) <= CONFIDENT_RSSI_DIFF_DB;
    }

    /**
     * The suggestion tier: same "went quiet, then a new face" shape as the merge, but over a
     * much longer gap because a duty-cycled beacon can stay silent for hours between windows.
     * Signal strength still has to agree within drift, and the result only feeds a question
     * the user answers - never a merge.
     */
    public static boolean couldBeLooseRotation(
            final long oldLastSeenMs, final int oldRssi,
            final long newFirstSeenMs, final int newRssi, final long nowMs) {
        if (nowMs - oldLastSeenMs < QUIET_FOR_MS) {
            return false;
        }
        final long gap = newFirstSeenMs - oldLastSeenMs;
        if (gap < 0 || gap > SUGGEST_GAP_MS) {
            return false;
        }
        return Math.abs(newRssi - oldRssi) <= SUGGEST_RSSI_DIFF_DB;
    }

    /**
     * Whether two stamped positions could be the same standing tag. Returns true when either
     * side lacks a fix - a missing position is no evidence against the pair, only an absent
     * one. A plain haversine; accuracy fields widen nothing here because the suggestion is a
     * question, not a verdict.
     */
    public static boolean samePlace(
            @Nullable final Double lat1, @Nullable final Double lon1,
            @Nullable final Double lat2, @Nullable final Double lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return true;
        }
        final double dLat = Math.toRadians(lat2 - lat1);
        final double dLon = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        final double metres = 2 * 6_371_000 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return metres <= SUGGEST_MAX_DISTANCE_M;
    }
}
