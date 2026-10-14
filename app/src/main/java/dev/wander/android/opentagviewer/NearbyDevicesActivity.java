package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static dev.wander.android.opentagviewer.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.ble.ForeignTagIdLog;
import dev.wander.android.opentagviewer.ble.ForeignTagNames;
import dev.wander.android.opentagviewer.ble.NearbyDeviceBook;
import dev.wander.android.opentagviewer.ble.NearbyDeviceScanner;
import dev.wander.android.opentagviewer.ble.NearbyDeviceSighting;
import dev.wander.android.opentagviewer.ble.NearbyRotationHeuristic;
import dev.wander.android.opentagviewer.databinding.ActivityNearbyDevicesBinding;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.ui.nearbydevices.ForeignTagRename;
import dev.wander.android.opentagviewer.ui.nearbydevices.NearbyDeviceListAdaptor;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Every Find My device the radio can hear, not only the user's own tags.
 *
 * <p><b>The screen {@link NearbyDeviceScanner} exists for.</b> It subscribes while it is on top
 * - the radio listens only while somebody is looking, which is what keeps a display feature
 * from being a recording - and shows one row per key prefix through {@link NearbyDeviceBook},
 * named via {@link ForeignTagNames} when the user has renamed it.
 *
 * <p>The list is refreshed on two triggers, and needs both. New sightings arrive from the scan
 * subscription, but the other half of a live list is *departures*: a tag that leaves range
 * sends nothing, so a periodic pass rebuilds the snapshot for the pruning and the "last seen"
 * age even while the air is quiet.
 */
public class NearbyDevicesActivity extends AppCompatActivity {
    private static final String TAG = NearbyDevicesActivity.class.getSimpleName();

    private static final int NEARBY_PERMISSION_REQUEST_CODE = 0x4E42;

    /**
     * How often the list is rebuilt while open, independent of arriving frames. One second is
     * what makes the "last seen" column read like a clock rather than a frozen snapshot, and it
     * costs a sort over a handful of rows.
     */
    private static final long REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1);

    /** Resubscribe interval after the scan ended mid-session; mirrors MapsActivity's choice. */
    private static final long SCAN_RETRY_DELAY_MS = TimeUnit.SECONDS.toMillis(30);

    private final NearbyDeviceScanner scanner = new NearbyDeviceScanner();
    private final NearbyDeviceBook book = new NearbyDeviceBook();
    private ForeignTagNames names;
    private ForeignTagIdLog idLog;

    /**
     * Prefixes the user already answered "different device" for, this session.
     *
     * <p>Session-only on purpose: persisting a rejection would freeze the answer even after
     * the tag really did rotate again, while the cost of re-asking next launch is one banner.
     */
    private final Set<String> dismissedSuggestions = new HashSet<>();

    /** The pair currently offered in the banner, or null while none is shown. */
    private String suggestedOldPrefix;
    private String suggestedNewPrefix;

    private ActivityNearbyDevicesBinding binding;
    private NearbyDeviceListAdaptor adaptor;

    private Disposable scanSubscription;
    private Disposable refreshSubscription;

    /**
     * The pending resubscribe after the scan ended mid-session - Bluetooth toggled off or a
     * failed scan both arrive as a quiet {@code onComplete}, so without this the screen keeps
     * claiming "nothing in range" forever while showing a radio nobody listens on.
     */
    private Disposable scanRetrySubscription;

    /**
     * True between the scan completing mid-session and a resubscribe that stayed alive. The
     * empty state reads from it: a dead radio needs "turn Bluetooth on", not "no devices".
     */
    private boolean scanUnavailable;

    /** Asked at most once per screen, for the same reason MapsActivity bounds its own request. */
    private boolean blePermissionRequested = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.names = new ForeignTagNames(this);
        this.idLog = new ForeignTagIdLog(this.getApplicationContext().getFilesDir());

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_nearby_devices);
        WindowPaddingUtil.insetForSystemBars(this.binding.getRoot());
        this.binding.setHandleClickBack(this::finish);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.adaptor = new NearbyDeviceListAdaptor(
                this.getResources(),
                this.names::nameFor,
                this::onDeviceClicked,
                this::onDeviceLongPressed);

        final RecyclerView list = findViewById(R.id.nearby_devices_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(this.adaptor);

        this.binding.nearbyRotationConfirm.setOnClickListener(v -> this.onRotationConfirmed());
        this.binding.nearbyRotationDismiss.setOnClickListener(v -> this.onRotationDismissed());
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!BlePermissions.granted(this)) {
            if (!this.blePermissionRequested) {
                this.blePermissionRequested = true;
                Log.d(TAG, "Requesting BLE permission(s) to scan nearby devices");
                ActivityCompat.requestPermissions(
                        this, BlePermissions.required(), NEARBY_PERMISSION_REQUEST_CODE);
            }
            this.showEmptyState(R.string.nearby_devices_needs_permission);
            return;
        }

        this.startScanning();

        this.refreshSubscription = Observable
                .interval(REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS, Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tick -> this.rebuildList(),
                        error -> Log.e(TAG, "Nearby device refresh failed", error));
    }

    @Override
    protected void onPause() {
        if (this.scanSubscription != null) {
            this.scanSubscription.dispose();
            this.scanSubscription = null;
        }
        if (this.refreshSubscription != null) {
            this.refreshSubscription.dispose();
            this.refreshSubscription = null;
        }
        if (this.scanRetrySubscription != null) {
            this.scanRetrySubscription.dispose();
            this.scanRetrySubscription = null;
        }
        this.idLog.flush();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            @NonNull final String[] permissions,
            @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != NEARBY_PERMISSION_REQUEST_CODE) {
            return;
        }
        // The permission dialog pauses the activity, so the framework's own onResume after it
        // starts the scan. Calling onResume here would subscribe twice - onPause then disposes
        // only the second pair and leaves a scan running on a closed screen.
        if (!BlePermissions.granted(this)) {
            this.showEmptyState(R.string.nearby_devices_needs_permission);
        }
    }

    private void startScanning() {
        this.scanUnavailable = false;
        this.scanSubscription = this.scanner.scan(this.getApplicationContext())
                .subscribeOn(Schedulers.io())
                // File IO belongs on the scan thread, not after observeOn: a flushed write on
                // the main thread would hitch the list every few seconds.
                .doOnNext(this.idLog::hear)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onSighting,
                        error -> Log.e(TAG, "Nearby device scan failed", error),
                        this::onScanEnded);
    }

    /**
     * The scan completed while the screen was open: Bluetooth went off, or the platform
     * refused the scan. Retry like {@code MapsActivity#onNearbyWatchEnded} - one attempt per
     * 30 seconds stays far under the scan-start budget and self-heals when the radio returns.
     */
    private void onScanEnded() {
        this.scanSubscription = null;
        this.scanUnavailable = true;
        this.rebuildList();

        Log.i(TAG, "The nearby device scan ended mid-session; retrying in 30s");
        this.scanRetrySubscription = Observable
                .timer(SCAN_RETRY_DELAY_MS, TimeUnit.MILLISECONDS,
                        AndroidSchedulers.mainThread())
                .subscribe(tick -> {
                    if (BlePermissions.granted(this)) {
                        this.startScanning();
                    }
                });
    }

    private void onSighting(final NearbyDeviceSighting sighting) {
        this.book.hear(sighting);
        this.rebuildList();
    }

    private void rebuildList() {
        final List<NearbyDeviceSighting> rows =
                this.book.snapshot(System.currentTimeMillis());

        if (rows.isEmpty()) {
            this.showEmptyState(this.scanUnavailable
                    ? R.string.nearby_devices_scan_unavailable
                    : R.string.nearby_devices_empty);
            return;
        }

        this.binding.nearbyDevicesList.setVisibility(VISIBLE);
        this.binding.nearbyDevicesEmptyState.setVisibility(GONE);
        this.adaptor.setRows(rows);
        this.adaptor.notifyDataSetChanged();

        this.maybeOfferRotation(rows);
    }

    /**
     * The rotation question, asked once a plausible pair exists.
     *
     * <p>Runs on the one-second refresh rather than per frame: the heuristic waits for the
     * named prefix to go quiet, and quiet is a thing time decides, not a new advertisement.
     * Shows the closest match - smallest gap between the named prefix's last hearing and the
     * unnamed one's first - because several departed names and several new faces can overlap.
     */
    private void maybeOfferRotation(final List<NearbyDeviceSighting> rows) {
        if (this.suggestedNewPrefix != null) {
            return; // one question at a time, and it is already on screen
        }

        final long now = System.currentTimeMillis();
        final Map<String, String> names = this.names.all();
        long bestGap = Long.MAX_VALUE;
        String bestOld = null;
        String bestNew = null;

        for (final NearbyDeviceSighting row : rows) {
            final String newPrefix = row.getKeyPrefix();
            if (names.containsKey(newPrefix) || this.dismissedSuggestions.contains(newPrefix)) {
                continue;
            }
            final ForeignTagIdLog.Entry newEntry = this.idLog.entry(newPrefix);
            if (newEntry == null) {
                continue;
            }

            for (final Map.Entry<String, ForeignTagIdLog.Entry> old
                    : this.idLog.entries().entrySet()) {
                final String oldPrefix = old.getKey();
                if (newPrefix.equals(oldPrefix)
                        || !names.containsKey(oldPrefix)
                        || old.getValue().getRotatedInto() != null) {
                    continue;
                }
                if (!NearbyRotationHeuristic.couldBeRotation(
                        old.getValue().getLastSeenMs(), newEntry.getFirstSeenMs(), now)) {
                    continue;
                }
                final long gap = newEntry.getFirstSeenMs() - old.getValue().getLastSeenMs();
                if (gap < bestGap) {
                    bestGap = gap;
                    bestOld = oldPrefix;
                    bestNew = newPrefix;
                }
            }
        }

        if (bestNew != null) {
            this.suggestedOldPrefix = bestOld;
            this.suggestedNewPrefix = bestNew;
            this.binding.nearbyRotationText.setText(
                    getString(R.string.nearby_rotation_suggestion, names.get(bestOld)));
            this.binding.nearbyRotationBanner.setVisibility(VISIBLE);
        }
    }

    private void onRotationConfirmed() {
        if (this.suggestedOldPrefix == null || this.suggestedNewPrefix == null) {
            return;
        }
        final String name = this.names.nameFor(this.suggestedOldPrefix);
        this.names.rename(this.suggestedOldPrefix, null);
        this.names.rename(this.suggestedNewPrefix, name);
        this.idLog.markRotated(this.suggestedOldPrefix, this.suggestedNewPrefix);
        this.idLog.flush();
        this.hideRotationBanner();
        this.rebuildList();
    }

    private void onRotationDismissed() {
        if (this.suggestedNewPrefix != null) {
            this.dismissedSuggestions.add(this.suggestedNewPrefix);
        }
        this.hideRotationBanner();
    }

    private void hideRotationBanner() {
        this.suggestedOldPrefix = null;
        this.suggestedNewPrefix = null;
        this.binding.nearbyRotationBanner.setVisibility(GONE);
    }

    private void showEmptyState(final int textRes) {
        this.binding.nearbyDevicesList.setVisibility(GONE);
        this.binding.nearbyDevicesEmptyState.setVisibility(VISIBLE);
        this.binding.nearbyDevicesEmptyText.setText(textRes);
    }

    /** A click opens the pointing view for this prefix - the radar branch's screen. */
    private void onDeviceClicked(final NearbyDeviceSighting sighting) {
        final Intent intent = new Intent(this, NearbyRadarActivity.class);
        intent.putExtra(NearbyRadarActivity.EXTRA_KEY_PREFIX, sighting.getKeyPrefix());
        intent.putExtra(NearbyRadarActivity.EXTRA_MAC, sighting.getMac());
        this.startActivity(intent);
    }

    private void onDeviceLongPressed(final View anchor, final NearbyDeviceSighting sighting) {
        this.showRenameDialog(sighting.getKeyPrefix());
    }

    /** The shared foreign-tag name dialog, wired so the list redraws the renamed row. */
    private void showRenameDialog(final String keyPrefix) {
        ForeignTagRename.showDialog(this, this.names, keyPrefix, this::rebuildList);
    }
}
