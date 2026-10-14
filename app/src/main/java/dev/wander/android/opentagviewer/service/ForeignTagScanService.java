package dev.wander.android.opentagviewer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.NearbyDevicesActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.ble.ForeignTagIdLog;
import dev.wander.android.opentagviewer.ble.ForeignTagNames;
import dev.wander.android.opentagviewer.ble.ForeignTagWatch;
import dev.wander.android.opentagviewer.ble.NearbyDeviceScanner;
import dev.wander.android.opentagviewer.ble.NearbyPresenceTracker;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Tells the user when a *named* foreign Find My device arrives or leaves, while the app is
 * closed.
 *
 * <p><b>Opt-in, and narrower than {@link NearbyScanService}.</b> That service watches the
 * user's own tags and records history; this one only tracks prefixes the user bothered to name
 * in the nearby list, and only reports the two edges - appeared, left - through
 * {@link NearbyPresenceTracker}. Watching unnamed strangers would notify on every passer-by,
 * which is precisely the noise a name exists to filter.
 *
 * <p>Foreground because Android gives no other way to scan reliably in the background; the
 * permanent notification is the honest price. No location is read - the question is "is it
 * here", not "where" - so the service declares {@code connectedDevice} alone rather than
 * borrowing the location type it does not need.
 */
public class ForeignTagScanService extends Service {
    private static final String TAG = ForeignTagScanService.class.getSimpleName();

    /** The quiet ongoing channel - "a scan is running" is a status, not an alert. */
    private static final String CHANNEL_ID = "foreign_tag_watch";

    /**
     * The appear/disappear alerts' channel, loud enough to be heard. Versioned in the id for
     * the same reason NearbyScanService's alert channel is: channels are immutable once
     * created, so a different sound later means a new id, not a changed one.
     */
    private static final String ALERT_CHANNEL_ID = "foreign_tag_presence_v1";

    private static final int NOTIFICATION_ID = 4712;
    private static final int ALERT_ID_BASE = 47_000;

    /** Swiping the permanent notification away reads as "stop doing this", as in the sibling. */
    private static final String ACTION_DISMISSED =
            "dev.wander.opentagviewer.FOREIGN_SCAN_DISMISSED";

    /** How often silence is checked for departures. Cheap arithmetic, so the tick stays short. */
    private static final long SWEEP_INTERVAL_MS = 10_000;

    private final NearbyPresenceTracker tracker = new NearbyPresenceTracker();

    @Nullable
    private NearbyDeviceScanner scanner;

    @Nullable
    private Disposable watch;

    @Nullable
    private Disposable sweeper;

    private ForeignTagNames names;
    private ForeignTagIdLog idLog;

    /** Starts the service, or does nothing if it is already running. */
    public static void start(final Context context) {
        context.getApplicationContext().startForegroundService(
                new Intent(context.getApplicationContext(), ForeignTagScanService.class));
    }

    /** Stops the service and its scan. Safe to call when it is not running. */
    public static void stop(final Context context) {
        context.getApplicationContext().stopService(
                new Intent(context.getApplicationContext(), ForeignTagScanService.class));
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.names = new ForeignTagNames(this);
        this.idLog = ForeignTagIdLog.get(this);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null && ACTION_DISMISSED.equals(intent.getAction())) {
            // The swipe said stop; turning the setting off too keeps the switch honest - see
            // the ACTION_DISMISSED comment on the sibling service for why that is the reading.
            ForeignTagWatch.setEnabled(this, false);
            this.stopSelf();
            return START_NOT_STICKY;
        }

        this.goToForeground();
        this.startWatching();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (this.watch != null && !this.watch.isDisposed()) {
            this.watch.dispose();
        }
        if (this.sweeper != null && !this.sweeper.isDisposed()) {
            this.sweeper.dispose();
        }
        this.watch = null;
        this.sweeper = null;
        if (this.idLog != null) {
            this.idLog.flush();
        }
        super.onDestroy();
    }

    private void startWatching() {
        if (this.watch != null && !this.watch.isDisposed()) {
            return;
        }
        if (!BlePermissions.granted(this)) {
            Log.d(TAG, "Not watching foreign tags: BLE permission not granted");
            this.stopSelf();
            return;
        }

        // Balanced, not low latency: this runs all day and only needs edge detection, not the
        // live-RSSI feedback the radar wants. The sibling service's notes on scan modes apply.
        this.scanner = new NearbyDeviceScanner(ScanSettings.SCAN_MODE_BALANCED);

        this.watch = this.scanner.scan(this.getApplicationContext())
                .subscribeOn(Schedulers.io())
                .subscribe(this::onSighting,
                        error -> Log.w(TAG, "Foreign tag watch ended with an error", error),
                        () -> Log.i(TAG, "Foreign tag watch ended"));

        this.sweeper = Observable
                .interval(SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS,
                        Schedulers.io())
                .subscribe(tick -> this.checkDepartures(),
                        error -> Log.w(TAG, "The presence sweep stopped", error));

        Log.i(TAG, "Watching for named foreign tags");
    }

    private void onSighting(final dev.wander.android.opentagviewer.ble.NearbyDeviceSighting s) {
        this.idLog.hear(s);

        // Only named identities are tracked: an alert about a prefix nobody named is noise.
        if (this.names.nameFor(s.getKeyPrefix()) == null) {
            return;
        }
        for (final NearbyPresenceTracker.Transition t
                : this.tracker.hear(s.getKeyPrefix(), s.getSeenAtMs())) {
            this.notifyTransition(t);
        }
    }

    private void checkDepartures() {
        for (final NearbyPresenceTracker.Transition t
                : this.tracker.sweep(System.currentTimeMillis())) {
            this.notifyTransition(t);
        }
    }

    private void notifyTransition(final NearbyPresenceTracker.Transition t) {
        final String name = this.names.nameFor(t.getKeyPrefix());
        if (name == null) {
            return; // unnamed since the event - nothing meaningful to say
        }

        final int text = t.getEvent() == NearbyPresenceTracker.Event.APPEARED
                ? R.string.nearby_presence_appeared
                : R.string.nearby_presence_disappeared;

        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, NearbyDevicesActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification notification = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle(this.getString(text, name))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();

        // One notification id per prefix, so a tag flapping in and out updates its own entry
        // instead of stacking rows. floorMod keeps the id positive - hashCode() % n can go
        // negative, and a negative notify id is still a valid id we never reach again.
        this.getSystemService(NotificationManager.class)
                .notify(ALERT_ID_BASE + Math.floorMod(t.getKeyPrefix().hashCode(), 1000),
                        notification);
    }

    private void goToForeground() {
        final NotificationManager manager = this.getSystemService(NotificationManager.class);

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    this.getString(R.string.nearby_presence_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(
                    this.getString(R.string.nearby_presence_channel_description));
            manager.createNotificationChannel(channel);
        }
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            final NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    this.getString(R.string.nearby_presence_alert_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }

        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, NearbyDevicesActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Intent dismissed = new Intent(this, ForeignTagScanService.class)
                .setAction(ACTION_DISMISSED);
        final PendingIntent onSwipe = PendingIntent.getService(this, 1, dismissed,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(this.getString(R.string.nearby_presence_foreground_title))
                .setContentText(this.getString(R.string.nearby_presence_foreground_text))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentIntent(open)
                .setDeleteIntent(onSwipe)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            this.startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            this.startForeground(NOTIFICATION_ID, notification);
        }
    }
}
