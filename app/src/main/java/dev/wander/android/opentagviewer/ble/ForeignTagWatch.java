package dev.wander.android.opentagviewer.ble;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Whether the user wants presence alerts for named foreign tags, persisted.
 *
 * <p>One boolean in its own preferences file, next to the names and the sighting log rather
 * than inside either: names and history are data, this is the switch that decides whether a
 * service runs at all. Read live on every use so a toggle flipped in one place is honoured
 * everywhere the same moment, including by a service started before the flip.
 */
public final class ForeignTagWatch {

    private static final String PREFERENCES = "foreign_tag_watch";
    private static final String KEY_ENABLED = "presence_notify_enabled";

    private ForeignTagWatch() {
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(final Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(final Context context, final boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
}
