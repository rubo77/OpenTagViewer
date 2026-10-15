package dev.wander.android.opentagviewer;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.ble.ForeignTagNames;
import dev.wander.android.opentagviewer.ble.NearbyDeviceScanner;
import dev.wander.android.opentagviewer.ble.NearbyDeviceSighting;
import dev.wander.android.opentagviewer.databinding.ActivityNearbyRadarBinding;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.util.nearby.ProximityDistance;
import dev.wander.android.opentagviewer.util.nearby.RadarFeedback;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The point-at-it screen for one device picked off the nearby list.
 *
 * <p><b>Honest about what a single radio can do.</b> BLE gives a signal strength, not a
 * direction - the sweep is a metronome, not a compass needle. What the screen actually sells
 * is feedback speed: it runs the scan at {@code SCAN_MODE_LOW_LATENCY} so the beep rhythm and
 * the distance number react while somebody is still moving the phone, not a breath after.
 *
 * <p>Sound and vibration come from {@link RadarFeedback}'s metres curve, scheduled on a plain
 * {@link Handler}: each beep books the next one at the interval the *latest* distance calls
 * for, so the rhythm tightens as the person closes in without a tick that has to be fast
 * enough for the nearest case.
 */
public class NearbyRadarActivity extends AppCompatActivity {
    private static final String TAG = NearbyRadarActivity.class.getSimpleName();

    public static final String EXTRA_KEY_PREFIX = "dev.wander.opentagviewer.RADAR_KEY_PREFIX";
    public static final String EXTRA_MAC = "dev.wander.opentagviewer.RADAR_MAC";

    /** A tag unheard this long counts as lost: the screen freezes instead of lying. */
    private static final long LOST_AFTER_MS = TimeUnit.SECONDS.toMillis(15);

    /** Resubscribe interval after the scan ended mid-session; mirrors the list screen. */
    private static final long SCAN_RETRY_DELAY_MS = TimeUnit.SECONDS.toMillis(30);

    private final NearbyDeviceScanner scanner = new NearbyDeviceScanner();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ActivityNearbyRadarBinding binding;
    private ForeignTagNames names;

    private String keyPrefix;
    private Disposable scanSubscription;
    private Disposable scanRetrySubscription;

    /** True between a mid-session scan end and a resubscribe - the lost text reads it. */
    private boolean scanUnavailable;

    private ToneGenerator tones;
    private Vibrator vibrator;

    /** Latest heard distance in metres; NaN before the first sighting. */
    private double latestMeters = Double.NaN;
    private long lastHeardMs = 0L;

    /**
     * The self-rescheduling beep: fires once, books its own successor at the interval the
     * current distance calls for. It keeps running while the device is lost - silence is the
     * message there, but the runnable is also what notices a return and revives the rhythm.
     */
    private final Runnable beep = new Runnable() {
        @Override
        public void run() {
            if (isLost()) {
                showLost();
                handler.postDelayed(this, 500);
                return;
            }
            if (tones != null) {
                tones.startTone(ToneGenerator.TONE_PROP_BEEP, 60);
            }
            if (vibrator != null && RadarFeedback.shouldVibrate(latestMeters)) {
                vibrateOnce(50);
            }
            handler.postDelayed(this, RadarFeedback.beepIntervalMs(latestMeters));
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.names = new ForeignTagNames(this);
        this.keyPrefix = getIntent().getStringExtra(EXTRA_KEY_PREFIX);
        if (this.keyPrefix == null) {
            // A radar without a target has nothing to point at - and the heading below would
            // crash on the prefix substring first. Close rather than render a lie.
            finish();
            return;
        }

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_nearby_radar);
        WindowPaddingUtil.insetForSystemBars(this.binding.getRoot());
        this.binding.setHandleClickBack(this::finish);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.showDeviceHeading();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!BlePermissions.granted(this)) {
            this.showLost(R.string.nearby_devices_needs_permission);
            return;
        }

        this.tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        this.vibrator = obtainVibrator();

        this.startScanning();

        this.handler.post(this.beep);
    }

    @Override
    protected void onPause() {
        this.handler.removeCallbacks(this.beep);
        if (this.scanSubscription != null) {
            this.scanSubscription.dispose();
            this.scanSubscription = null;
        }
        if (this.scanRetrySubscription != null) {
            this.scanRetrySubscription.dispose();
            this.scanRetrySubscription = null;
        }
        if (this.tones != null) {
            this.tones.release();
            this.tones = null;
        }
        super.onPause();
    }

    private void onSighting(final NearbyDeviceSighting sighting) {
        if (!this.keyPrefix.equals(sighting.getKeyPrefix())) {
            return;
        }

        this.latestMeters = ProximityDistance.estimateMeters(sighting.getRssi());
        this.lastHeardMs = sighting.getSeenAtMs();

        this.binding.nearbyRadarDistance.setText(
                getString(R.string.nearby_device_distance_format,
                        ProximityDistance.describe(sighting.getRssi())));
        this.binding.nearbyRadarStatus.setText("");
        this.binding.nearbyRadarView.updateDevice((float) this.latestMeters, true);
    }

    /**
     * The vibrator service went through a rename on API 31; both spellings still work below
     * it, and the manager is the only non-deprecated path at and above it.
     */
    private Vibrator obtainVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final VibratorManager manager = getSystemService(VibratorManager.class);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    @SuppressWarnings("deprecation") // The plain overload is the only one below API 26.
    private void vibrateOnce(final long ms) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.vibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            this.vibrator.vibrate(ms);
        }
    }

    private void startScanning() {
        this.scanUnavailable = false;
        this.scanSubscription = this.scanner.scan(this.getApplicationContext())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onSighting,
                        error -> Log.e(TAG, "Radar scan failed", error),
                        this::onScanEnded);
    }

    /**
     * The scan completed while the screen was open - Bluetooth went off or the platform
     * refused. Without a resubscribe the radar would sit on its last reading forever, so
     * retry like the list screen and MapsActivity do: one attempt per 30 seconds.
     */
    private void onScanEnded() {
        this.scanSubscription = null;
        this.scanUnavailable = true;
        this.showLost();

        Log.i(TAG, "The radar scan ended mid-session; retrying in 30s");
        this.scanRetrySubscription = Observable
                .timer(SCAN_RETRY_DELAY_MS, TimeUnit.MILLISECONDS,
                        AndroidSchedulers.mainThread())
                .subscribe(tick -> {
                    if (BlePermissions.granted(this)) {
                        this.startScanning();
                    }
                });
    }

    private boolean isLost() {
        return this.lastHeardMs == 0L
                || System.currentTimeMillis() - this.lastHeardMs > LOST_AFTER_MS;
    }

    /** The frozen display for "nothing heard (any more)": no distance, still sweep, a note. */
    private void showLost() {
        this.showLost(this.scanUnavailable
                ? R.string.nearby_devices_scan_unavailable
                : R.string.nearby_radar_lost);
    }

    private void showLost(final int statusText) {
        this.binding.nearbyRadarDistance.setText("");
        this.binding.nearbyRadarStatus.setText(statusText);
        this.binding.nearbyRadarView.updateDevice(Float.NaN, false);
    }

    private void showDeviceHeading() {
        final String givenName = this.names.nameFor(this.keyPrefix);
        final String title = givenName != null
                ? givenName
                : getString(R.string.nearby_device_unnamed, this.keyPrefix.substring(0, 4));
        final TextView heading = findViewById(R.id.nearby_radar_device_name);
        heading.setText(title);
        final TextView mac = findViewById(R.id.nearby_radar_mac);
        mac.setText(getIntent().getStringExtra(EXTRA_MAC));
    }
}
