package dev.wander.android.opentagviewer.util.nearby;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement;
import dev.wander.android.opentagviewer.ble.ForeignTagIdLog;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * The export contract of {@link ForeignTagExport}: a reader on the other end sees exactly
 * these keys, joined by prefix.
 */
public class ForeignTagExportTest {

    private static ForeignTagIdLog.Entry entry(
            final long first, final long last, final int rssi, final String rotatedInto)
            throws Exception {
        final Constructor<ForeignTagIdLog.Entry> ctor =
                ForeignTagIdLog.Entry.class.getDeclaredConstructor(
                        long.class, long.class, int.class, String.class,
                        FindMyAdvertisement.BatteryLevel.class,
                        FindMyAdvertisement.State.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(first, last, rssi, null, null, null, rotatedInto);
    }

    @Test
    public void rendersNamesKeyedByPrefix() throws Exception {
        final Map<String, String> names = Map.of("aabbccddeeff", "Keys");

        final JSONObject root = new JSONObject(
                ForeignTagExport.toJson(names, new HashMap<>()));

        assertEquals("Keys", root.getJSONObject("names").getString("aabbccddeeff"));
    }

    @Test
    public void rendersSightingEntriesWithAllFields() throws Exception {
        final Map<String, ForeignTagIdLog.Entry> sightings = new HashMap<>();
        sightings.put("aabbccddeeff", entry(1_000, 9_000, -70, null));

        final JSONObject root = new JSONObject(
                ForeignTagExport.toJson(Map.of(), sightings));
        final JSONObject o = root.getJSONObject("sightings").getJSONObject("aabbccddeeff");

        assertEquals(1_000L, o.getLong("first"));
        assertEquals(9_000L, o.getLong("last"));
        assertEquals(-70, o.getInt("rssi"));
        assertFalse(o.has("rotatedInto"));
    }

    @Test
    public void keepsTheRotatedIntoLinkVisible() throws Exception {
        final Map<String, ForeignTagIdLog.Entry> sightings = new HashMap<>();
        sightings.put("aabbccddeeff", entry(1_000, 9_000, -70, "112233445566"));

        final JSONObject root = new JSONObject(
                ForeignTagExport.toJson(Map.of(), sightings));

        assertEquals("112233445566", root.getJSONObject("sightings")
                .getJSONObject("aabbccddeeff").getString("rotatedInto"));
    }

    @Test
    public void stillProducesBothSectionsWhenEmpty() throws Exception {
        final JSONObject root = new JSONObject(
                ForeignTagExport.toJson(Map.of(), Map.of()));

        assertTrue(root.has("names"));
        assertTrue(root.has("sightings"));
    }
}
