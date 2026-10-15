package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

import java.util.Map;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The rotation merge that needs no confirmation, shared by every place that
 * persists sightings.
 *
 * <p>{@link NearbyRotationHeuristic} has two verdicts. The loose one stays in
 * {@code NearbyDevicesActivity} as a suggestion the user confirms, because timing
 * alone can marry two strangers. The confident one - a new prefix appearing within
 * {@link NearbyRotationHeuristic#CONFIDENT_GAP_MS} of a departed prefix's last
 * hearing at a matching signal strength - describes a key rotation closely enough
 * to act on without asking: the identity simply carries over.
 *
 * <p>Acting on it here, in the hear-path, is what makes it reachable from
 * {@code ForeignTagScanService} - the rotation keeps happening while the list
 * screen is closed, and a merge only reachable from that screen would leave every
 * background rotation looking like a brand-new device.
 *
 * <p>The check runs on every persisted sighting rather than only on a prefix's
 * first frame, because {@link NearbyRotationHeuristic#QUIET_FOR_MS} means a merge
 * cannot be decided until the predecessor has been silent a while - each later
 * frame of the new prefix retries it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ForeignRotation {

    /**
     * Whether this sighting's prefix is provably the successor of a departed one -
     * and if so, makes it so on the spot: the rotation link is recorded and any
     * name moves across.
     *
     * @return the predecessor prefix when a merge happened, else null
     */
    @Nullable
    public static String autoMergeIfConfident(final NearbyDeviceSighting justHeard,
            final ForeignTagIdLog log, final ForeignTagNames names) {
        if (names.nameFor(justHeard.getKeyPrefix()) != null) {
            // An already-named prefix is an established identity; merging a
            // departed neighbour into it would overwrite a name the user chose.
            return null;
        }
        final String oldPrefix = pickConfidentPredecessor(
                justHeard.getKeyPrefix(), log, justHeard.getSeenAtMs());
        if (oldPrefix == null) {
            return null;
        }
        merge(oldPrefix, justHeard.getKeyPrefix(), log, names);
        return oldPrefix;
    }

    /**
     * The predecessor half of the confident merge: the still-unlinked prefix whose
     * death best lines up with {@code newPrefix}'s birth, by time and by signal
     * strength. Pure selection over the log - separated from the writes so the
     * whole rule sits under a JVM test.
     */
    @Nullable
    static String pickConfidentPredecessor(final String newPrefix,
            final ForeignTagIdLog log, final long nowMs) {
        final ForeignTagIdLog.Entry newEntry = log.entry(newPrefix);
        if (newEntry == null || newEntry.getRotatedInto() != null
                || nowMs - newEntry.getFirstSeenMs()
                        > NearbyRotationHeuristic.MAX_MERGE_GAP_MS) {
            // A prefix that already rotated further is dead, and one first heard
            // long ago has its rotation moment behind it - the RSSI stored for it
            // has been drifting ever since, so a match against it means nothing.
            return null;
        }

        String bestOld = null;
        long bestGap = Long.MAX_VALUE;
        for (final Map.Entry<String, ForeignTagIdLog.Entry> old
                : log.entries().entrySet()) {
            final ForeignTagIdLog.Entry oldEntry = old.getValue();
            if (newPrefix.equals(old.getKey()) || oldEntry.getRotatedInto() != null) {
                continue;
            }
            if (!NearbyRotationHeuristic.isConfidentRotation(
                    oldEntry.getLastSeenMs(), oldEntry.getLastRssi(),
                    newEntry.getFirstSeenMs(), newEntry.getLastRssi(), nowMs)) {
                continue;
            }
            final long gap = newEntry.getFirstSeenMs() - oldEntry.getLastSeenMs();
            if (gap < bestGap) {
                bestGap = gap;
                bestOld = old.getKey();
            }
        }
        return bestOld;
    }

    /**
     * Records {@code oldPrefix} as rotated into {@code newPrefix} and carries the
     * name over - the write both the confident merge above and the confirmed
     * suggestion in {@code NearbyDevicesActivity} funnel through.
     */
    public static void merge(final String oldPrefix, final String newPrefix,
            final ForeignTagIdLog log, final ForeignTagNames names) {
        final String name = names.nameFor(oldPrefix);
        if (name != null) {
            names.rename(oldPrefix, null);
            names.rename(newPrefix, name);
        }
        log.markRotated(oldPrefix, newPrefix);
        log.flush();
    }
}
