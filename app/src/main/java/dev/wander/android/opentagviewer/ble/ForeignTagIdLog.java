package dev.wander.android.opentagviewer.ble;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import lombok.Getter;
import android.util.Log;

/**
 * What each foreign key prefix was heard doing, kept across app restarts.
 *
 * <p><b>A JSON file, and deliberately not Room</b> - the same reasoning {@link ForeignTagNames}
 * carries: an append-mostly log of a dozen fields per prefix is not a schema, and the project's
 * first rule prices Room migrations accordingly. The log survives process death because the
 * rotation heuristic's question - "did the named tag that just went quiet reappear under a new
 * key while I was not looking?" - is most interesting exactly after the app was killed and
 * reopened.
 *
 * <p>Writes are rate-limited ({@link #FLUSH_EVERY_MS}) rather than per sighting: a tag in range
 * advertises every one to three seconds, and a flash write per advertisement is the kind of
 * wear nobody thanks you for. {@link #flush} is public so the screen can push the last changes
 * out on its way down.
 *
 * <p>All public methods are synchronized because the writers and readers sit on different
 * threads by design: {@code hear} runs on the scan thread (RxJava {@code doOnNext} before the
 * UI hop) while {@link #entry}, {@link #entries} and {@link #flush} run on the main thread.
 * An unsynchronized HashMap read mid-{@code put} is a ConcurrentModificationException or a
 * torn resize, and neither fails loudly at a distance a user could report.
 */
public final class ForeignTagIdLog {

    static final String FILE_NAME = "foreign_tag_sightings.json";

    /** How often {@link #hear} may actually touch flash; {@link #flush} bypasses this. */
    static final long FLUSH_EVERY_MS = 10_000;

    /**
     * The log is bounded so it cannot grow without limit: a tag rolls its key every ~15
     * minutes, so one tag alone adds ~96 entries a day, and the whole map is serialised on
     * every flush and walked by the rotation check every second. Past this many entries the
     * oldest-by-last-heard drop out - plenty of history for rotation detection and exports,
     * and a file that can never outgrow the device it lives on.
     */
    static final int MAX_ENTRIES = 4_096;

    private final File file;
    private final Map<String, Entry> entries = new HashMap<>();
    private long lastFlushMs = 0L;
    private boolean dirty = false;

    /** One prefix's record: first and last hearing, the freshest radio facts, and where it rotated to. */
    @Getter
    public static final class Entry {
        private final long firstSeenMs;
        private long lastSeenMs;
        private int lastRssi;

        /**
         * The last frame's facts, kept so a named device can be listed while unheard: the
         * list rebuilds an honest "what we last saw" row from these rather than inventing
         * values. Null on records written before the fields existed.
         */
        @Nullable
        private String lastMac;
        @Nullable
        private FindMyAdvertisement.BatteryLevel lastBatteryLevel;
        @Nullable
        private FindMyAdvertisement.State lastState;

        /**
         * The prefix this identity was confirmed to have rotated into, or null.
         *
         * <p>Once set, the old prefix is dead - the tag broadcasts the successor now, and a
         * sighting of this prefix again would mean a different device reusing bytes, so the
         * heuristic ignores retired entries entirely.
         */
        @Nullable
        private String rotatedInto;

        Entry(final long firstSeenMs, final long lastSeenMs, final int lastRssi,
              @Nullable final String lastMac,
              @Nullable final FindMyAdvertisement.BatteryLevel lastBatteryLevel,
              @Nullable final FindMyAdvertisement.State lastState,
              @Nullable final String rotatedInto) {
            this.firstSeenMs = firstSeenMs;
            this.lastSeenMs = lastSeenMs;
            this.lastRssi = lastRssi;
            this.lastMac = lastMac;
            this.lastBatteryLevel = lastBatteryLevel;
            this.lastState = lastState;
            this.rotatedInto = rotatedInto;
        }

        /**
         * The last hearing under {@code keyPrefix}, rebuilt as a sighting: real values where
         * the log kept them, null where it did not. The row this feeds renders offline, so
         * nothing here claims to be current.
         */
        public NearbyDeviceSighting toSighting(final String keyPrefix) {
            return NearbyDeviceSighting.builder()
                    .keyPrefix(keyPrefix)
                    .mac(this.lastMac)
                    .rssi(this.lastRssi)
                    .batteryLevel(this.lastBatteryLevel)
                    .state(this.lastState)
                    .seenAtMs(this.lastSeenMs)
                    .build();
        }
    }

    @Nullable
    private static ForeignTagIdLog shared;

    /**
     * The one instance the process shares.
     *
     * <p><b>There must not be two of these.</b> The list screen and the presence service both
     * keep this log: two instances would load once and then overwrite each other's additions
     * on every flush - the survivor would be whoever wrote last, and the loser's whole
     * session of sightings would vanish from the file. One shared map makes the file the
     * serialization point instead of a casualty of it.
     */
    public static synchronized ForeignTagIdLog get(final Context context) {
        if (shared == null) {
            shared = new ForeignTagIdLog(
                    context.getApplicationContext().getFilesDir());
        }
        return shared;
    }

    /** Direct construction for tests that want their own file rather than the shared one. */
    @VisibleForTesting
    public ForeignTagIdLog(final File filesDir) {
        this.file = new File(filesDir, FILE_NAME);
        this.load();
    }

    /**
     * Records a sighting: creates the entry on first contact, otherwise advances last-seen and
     * the freshest RSSI. Flushes when {@link #FLUSH_EVERY_MS} has passed since the last write.
     */
    public synchronized void hear(final NearbyDeviceSighting sighting) {
        final Entry entry = this.entries.get(sighting.getKeyPrefix());
        if (entry == null) {
            this.entries.put(sighting.getKeyPrefix(), new Entry(
                    sighting.getSeenAtMs(), sighting.getSeenAtMs(), sighting.getRssi(),
                    sighting.getMac(), sighting.getBatteryLevel(), sighting.getState(), null));
            this.evictOldestPastLimit();
        } else if (sighting.getSeenAtMs() >= entry.getLastSeenMs()) {
            entry.lastSeenMs = sighting.getSeenAtMs();
            entry.lastRssi = sighting.getRssi();
            entry.lastMac = sighting.getMac();
            entry.lastBatteryLevel = sighting.getBatteryLevel();
            entry.lastState = sighting.getState();
        }
        this.dirty = true;

        if (sighting.getSeenAtMs() - this.lastFlushMs > FLUSH_EVERY_MS) {
            this.flush();
        }
    }

    /**
     * Drops the entries nobody has heard from longest once the log is full. Rotated-away
     * prefixes go first - their purpose is linking a name across rotations, which the newest
     * link already serves.
     */
    private void evictOldestPastLimit() {
        while (this.entries.size() > MAX_ENTRIES) {
            String oldest = null;
            long oldestSeen = Long.MAX_VALUE;
            boolean oldestRotated = false;
            for (final Map.Entry<String, Entry> e : this.entries.entrySet()) {
                final boolean rotated = e.getValue().getRotatedInto() != null;
                final long seen = e.getValue().getLastSeenMs();
                if (oldest == null
                        || (rotated && !oldestRotated)
                        || (rotated == oldestRotated && seen < oldestSeen)) {
                    oldest = e.getKey();
                    oldestSeen = seen;
                    oldestRotated = rotated;
                }
            }
            if (oldest == null) {
                return;
            }
            this.entries.remove(oldest);
        }
    }

    /** What is on record for a prefix, or null if never heard. */
    @Nullable
    public synchronized Entry entry(final String keyPrefix) {
        return this.entries.get(keyPrefix);
    }

    /** Every prefix on record - the rotation heuristic and the export both read this. */
    public synchronized Map<String, Entry> entries() {
        return new HashMap<>(this.entries);
    }

    /** Marks {@code oldPrefix} as retired in favour of {@code newPrefix}. */
    public synchronized void markRotated(final String oldPrefix, final String newPrefix) {
        final Entry entry = this.entries.get(oldPrefix);
        if (entry != null) {
            entry.rotatedInto = newPrefix;
            this.dirty = true;
        }
    }

    /** Writes pending changes now, e.g. while a screen pauses. */
    public synchronized void flush() {
        if (!this.dirty) {
            return;
        }
        try {
            final JSONObject root = new JSONObject();
            for (final Map.Entry<String, Entry> e : this.entries.entrySet()) {
                final JSONObject o = new JSONObject();
                o.put("first", e.getValue().getFirstSeenMs());
                o.put("last", e.getValue().getLastSeenMs());
                o.put("rssi", e.getValue().getLastRssi());
                if (e.getValue().getLastMac() != null) {
                    o.put("mac", e.getValue().getLastMac());
                }
                if (e.getValue().getLastBatteryLevel() != null) {
                    o.put("battery", e.getValue().getLastBatteryLevel().name());
                }
                if (e.getValue().getLastState() != null) {
                    o.put("state", e.getValue().getLastState().name());
                }
                if (e.getValue().getRotatedInto() != null) {
                    o.put("rotatedInto", e.getValue().getRotatedInto());
                }
                root.put(e.getKey(), o);
            }
            final File staging = new File(this.file.getParentFile(),
                    this.file.getName() + ".tmp");
            Files.write(staging.toPath(),
                    root.toString().getBytes(StandardCharsets.UTF_8));
            // A crash between write and rename must not leave half a JSON document behind:
            // the next load() would then start empty and lose every name's history at once.
            Files.move(staging.toPath(), this.file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            this.dirty = false;
            this.lastFlushMs = System.currentTimeMillis();
        } catch (final IOException | JSONException cannotPersist) {
            // The log is a convenience: a failed write loses history, not correctness - the
            // in-memory map still serves this session, and the next flush retries.
            Log.w("ForeignTagIdLog", "Could not persist the sighting log",
                    cannotPersist);
        }
    }

    private void load() {
        if (!this.file.exists()) {
            return;
        }
        try {
            final String raw = new String(
                    Files.readAllBytes(this.file.toPath()), StandardCharsets.UTF_8);
            final JSONObject root = new JSONObject(raw);
            for (final Iterator<String> it = root.keys(); it.hasNext(); ) {
                final String prefix = it.next();
                final JSONObject o = root.getJSONObject(prefix);
                this.entries.put(prefix, new Entry(
                        o.getLong("first"),
                        o.getLong("last"),
                        o.optInt("rssi", 0),
                        o.has("mac") ? o.getString("mac") : null,
                        enumField(o, "battery", FindMyAdvertisement.BatteryLevel.class, prefix),
                        enumField(o, "state", FindMyAdvertisement.State.class, prefix),
                        o.has("rotatedInto") ? o.getString("rotatedInto") : null));
            }
        } catch (final IOException | JSONException unreadable) {
            // A corrupt log is worth less than nothing: starting empty loses history, trusting
            // it would poison the merge suggestions with entries that were never seen.
            Log.w("ForeignTagIdLog", "Ignoring an unreadable sighting log",
                    unreadable);
        }
    }

    /**
     * Reads an enum field stored by name, tolerating names no enum knows. A value that cannot
     * parse is reported, then treated as unrecorded - the same state records written before
     * the field existed are already in - rather than discarding the whole entry over one key.
     */
    @Nullable
    private static <E extends Enum<E>> E enumField(
            final JSONObject o, final String key, final Class<E> type, final String prefix) {
        if (!o.has(key)) {
            return null;
        }
        try {
            return Enum.valueOf(type, o.getString(key));
        } catch (final IllegalArgumentException | JSONException unknownName) {
            Log.w("ForeignTagIdLog",
                    "Ignoring unparseable " + key + " in the record for " + prefix,
                    unknownName);
            return null;
        }
    }

    /** Test hook: the file it writes to. */
    @VisibleForTesting
    File file() {
        return this.file;
    }
}
