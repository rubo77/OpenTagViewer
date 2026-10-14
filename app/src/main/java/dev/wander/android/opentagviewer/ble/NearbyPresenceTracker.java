package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.Getter;

/**
 * Which watched prefixes are present right now, and the moments that changed.
 *
 * <p><b>A state machine, not a log.</b> The screen answers "what is around" continuously; a
 * presence alert instead wants the two edges - "turned up" and "went away" - and nothing in
 * between. Hearing a tag that is already present must produce nothing, and a tag gone quiet
 * counts as left only after {@link #GONE_AFTER_MS} of silence: Find My frames arrive every few
 * seconds when they arrive at all, but an obstructed tag can stutter far longer, and calling a
 * departure on the first gap would alert on a coat draped over a bag.
 *
 * <p>Pure - no Android, no clock of its own. Every method takes "now" so the whole transition
 * table is a JVM test and the service supplies {@code System.currentTimeMillis()}.
 */
public final class NearbyPresenceTracker {

    /**
     * Silence long enough to call a departure.
     *
     * <p>Two minutes, not the list's 30 seconds: a row vanishing early is a display
     * simplification, but a phone buzzing "gone" for a tag still in the room is a false alarm
     * that teaches the user to ignore the real ones.
     */
    static final long GONE_AFTER_MS = 120_000;

    /** What changed for a prefix. */
    public enum Event { APPEARED, DISAPPEARED }

    /** One edge: this prefix did this, at the timestamp the caller supplied. */
    @Getter
    public static final class Transition {
        private final String keyPrefix;
        private final Event event;

        private Transition(final String keyPrefix, final Event event) {
            this.keyPrefix = keyPrefix;
            this.event = event;
        }
    }

    private final Map<String, Long> lastSeenMs = new HashMap<>();
    private final Set<String> present = new HashSet<>();

    /**
     * Records a sighting; returns the transitions it caused - at most an APPEARED for a prefix
     * that was absent. Returns a list so a future batched caller cannot lose an event.
     */
    public synchronized List<Transition> hear(final String keyPrefix, final long nowMs) {
        this.lastSeenMs.put(keyPrefix, nowMs);
        if (this.present.add(keyPrefix)) {
            return List.of(new Transition(keyPrefix, Event.APPEARED));
        }
        return List.of();
    }

    /**
     * Finds departures - present prefixes unheard for {@link #GONE_AFTER_MS}. Called on a tick
     * by whoever owns the tracker, since silence sends no frame of its own.
     */
    public synchronized List<Transition> sweep(final long nowMs) {
        final List<Transition> transitions = new ArrayList<>();
        for (final String prefix : new HashSet<>(this.present)) {
            final Long seen = this.lastSeenMs.get(prefix);
            if (seen != null && nowMs - seen > GONE_AFTER_MS) {
                this.present.remove(prefix);
                transitions.add(new Transition(prefix, Event.DISAPPEARED));
            }
        }
        return transitions;
    }

    /** Whether the prefix currently counts as here. */
    public synchronized boolean isPresent(final String keyPrefix) {
        return this.present.contains(keyPrefix);
    }

    /**
     * Drops everything known about a prefix - e.g. when a name moved to a rotated successor
     * and the old bytes are somebody else's now.
     */
    public synchronized void forget(final String keyPrefix) {
        this.present.remove(keyPrefix);
        this.lastSeenMs.remove(keyPrefix);
    }
}
