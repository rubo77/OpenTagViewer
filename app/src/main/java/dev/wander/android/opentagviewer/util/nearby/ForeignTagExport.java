package dev.wander.android.opentagviewer.util.nearby;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

import dev.wander.android.opentagviewer.ble.ForeignTagIdLog;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The nearby-devices data as one JSON document: the names the user gave, and the sighting
 * history the log kept.
 *
 * <p><b>Pure serialization, no Android.</b> The shape of what leaves the phone is a contract
 * worth a test of its own - anybody parsing the export on the other end should be able to rely
 * on exactly these keys, which is why it lives in util rather than inside the share-intent
 * plumbing that merely hands the string to a file.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ForeignTagExport {

    /**
     * Renders names and sighting entries to a JSON object string:
     * {@code {"names": {prefix: name}, "sightings": {prefix: {first,last,rssi,rotatedInto}}}}.
     *
     * <p>Keyed by the same prefix both halves use, so a reader can join without knowing our
     * internals - and a prefix whose name moved to a rotated successor stays visible through
     * {@code rotatedInto} rather than silently vanishing.
     */
    public static String toJson(
            final Map<String, String> names,
            final Map<String, ForeignTagIdLog.Entry> sightings) {
        try {
            final JSONObject namesJson = new JSONObject();
            for (final Map.Entry<String, String> e : names.entrySet()) {
                namesJson.put(e.getKey(), e.getValue());
            }

            final JSONObject sightingsJson = new JSONObject();
            for (final Map.Entry<String, ForeignTagIdLog.Entry> e : sightings.entrySet()) {
                final JSONObject o = new JSONObject();
                o.put("first", e.getValue().getFirstSeenMs());
                o.put("last", e.getValue().getLastSeenMs());
                o.put("rssi", e.getValue().getLastRssi());
                if (e.getValue().getRotatedInto() != null) {
                    o.put("rotatedInto", e.getValue().getRotatedInto());
                }
                sightingsJson.put(e.getKey(), o);
            }

            final JSONObject root = new JSONObject();
            root.put("names", namesJson);
            root.put("sightings", sightingsJson);
            return root.toString(2);
        } catch (final JSONException cannotRender) {
            // JSONObject.put on plain maps/strings cannot actually throw, so if it ever does
            // the failure must be loud - an empty document would look like an empty log and
            // quietly erase a user's history at the share sheet.
            throw new IllegalStateException("The export document failed to serialise",
                    cannotRender);
        }
    }
}
