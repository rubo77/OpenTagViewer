package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;


import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.ble.DerivedAddressStore;
import dev.wander.android.opentagviewer.ble.ForeignRotation;
import dev.wander.android.opentagviewer.ble.ForeignTagIdLog;
import dev.wander.android.opentagviewer.ble.ForeignTagNames;
import dev.wander.android.opentagviewer.ble.ForeignTagWatch;
import dev.wander.android.opentagviewer.ble.NearbyDeviceBook;
import dev.wander.android.opentagviewer.ble.NearbyDeviceScanner;
import dev.wander.android.opentagviewer.ble.NearbyDeviceSighting;
import dev.wander.android.opentagviewer.ble.NearbyRotationHeuristic;
import dev.wander.android.opentagviewer.databinding.ActivityNearbyDevicesBinding;
import dev.wander.android.opentagviewer.service.ForeignTagScanService;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.ui.login.OfflineBanner;
import dev.wander.android.opentagviewer.ui.nearbydevices.ForeignTagRename;
import dev.wander.android.opentagviewer.ui.nearbydevices.NearbyDeviceListAdaptor;
import dev.wander.android.opentagviewer.util.android.CachedPhoneLocation;
import dev.wander.android.opentagviewer.util.android.FusedPhoneLocation;
import dev.wander.android.opentagviewer.util.android.PhoneLocation;
import dev.wander.android.opentagviewer.util.nearby.ForeignTagExport;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import androidx.core.content.FileProvider;
import java.nio.charset.StandardCharsets;

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
    private PhoneLocation phoneLocation;

    /** The pair currently offered in the banner, or null while none is shown. */
    private String suggestedOldPrefix;
    private String suggestedNewPrefix;

    private ActivityNearbyDevicesBinding binding;
    private NearbyDeviceListAdaptor adaptor;

    private Disposable scanSubscription;
    private Disposable refreshSubscription;
    /** The account/Bluetooth watch feeding the banner; resumes and pauses with the screen. */
    private Disposable offlineBannerWatch;

    /**
     * Prefixes marked by long-press. The set being non-empty IS the selection mode - when the
     * last mark comes off, the bar and the check icons disappear on their own.
     */
    private final Set<String> selectedPrefixes = new HashSet<>();

    /** Back ends selection mode instead of the screen; enabled only while it has rows. */
    private OnBackPressedCallback selectionBackCallback;

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

    /** The row filter over the list - one rule at a time, matching the chip row's selection. */
    private enum NearbyFilter { ALL, ONLINE, OWN, NAMED, UNNAMED }

    private NearbyFilter filter = NearbyFilter.ALL;

    /** The addresses this phone's own tags were ever derived to advertise on. */
    private DerivedAddressStore derivedAddresses;
    private Set<String> ownMacs = Set.of();
    private long ownMacsLoadedMs = 0L;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.names = new ForeignTagNames(this);
        this.idLog = ForeignTagIdLog.get(this);
        this.derivedAddresses =
                new DerivedAddressStore(this.getApplicationContext().getFilesDir());
        this.phoneLocation = new CachedPhoneLocation(
                new FusedPhoneLocation(this.getApplicationContext()));

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_nearby_devices);
        WindowPaddingUtil.insetForSystemBars(this.binding.getRoot());
        this.binding.setHandleClickBack(this::finish);
        this.binding.setHandleClickMap(
                () -> this.startActivity(new Intent(this, MapsActivity.class)));

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

        this.binding.nearbyPresenceSwitch.setOnCheckedChangeListener(
                (button, checked) -> this.onPresenceWatchToggled(checked));

        this.binding.nearbyFilterChips.setOnCheckedStateChangeListener(
                (group, checkedIds) -> this.onFilterChanged(
                        checkedIds.isEmpty() ? -1 : checkedIds.get(0)));
        this.binding.nearbyExportButton.setOnClickListener(v -> this.shareLog());

        this.binding.nearbySelectionRename.setOnClickListener(v -> this.renameSelected());
        this.binding.nearbySelectionDelete.setOnClickListener(v -> this.confirmDeleteSelected());
        this.binding.nearbySelectionClose.setOnClickListener(v -> this.clearSelection());

        this.selectionBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                NearbyDevicesActivity.this.clearSelection();
            }
        };
        this.getOnBackPressedDispatcher().addCallback(this, this.selectionBackCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();

        this.offlineBannerWatch = OfflineBanner.watch(this, this.binding.offlineModeBanner.getRoot());

        // The switch mirrors the stored setting rather than the service's liveness - it can
        // be off while a stop is still winding down, and "requested" is what the user flipped.
        this.binding.nearbyPresenceSwitch.setChecked(ForeignTagWatch.isEnabled(this));

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
        if (this.offlineBannerWatch != null) {
            this.offlineBannerWatch.dispose();
            this.offlineBannerWatch = null;
        }
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
                .doOnNext(s -> {
                    this.idLog.hear(s, this.phoneLocation.lastKnown());
                    // The merge belongs in the hear-path, not in the visible rows: a
                    // rotation the screen never sees (background service, or a frame
                    // swallowed between ticks) still has to carry the name over.
                    if (ForeignRotation.autoMergeIfConfident(s, this.idLog, this.names)
                            != null) {
                        final String name = this.names.nameFor(s.getKeyPrefix());
                        this.runOnUiThread(() -> {
                            if (name != null) {
                                Toast.makeText(this, getString(
                                        R.string.nearby_rotation_migrated, name),
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                })
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
        if (this.filter == NearbyFilter.OWN) {
            this.refreshOwnMacsIfStale();
        }

        final long nowMs = System.currentTimeMillis();
        final List<NearbyDeviceSighting> rows = this.book.snapshot(nowMs);
        // A prefix already linked to a successor is the same device shown twice - the live
        // row under the new key carries the name and the history, the old one is a corpse.
        rows.removeIf(s -> {
            final ForeignTagIdLog.Entry record = this.idLog.entry(s.getKeyPrefix());
            return record != null && record.getRotatedInto() != null;
        });
        this.addUnheardNamedRows(rows, nowMs);
        final List<NearbyDeviceSighting> filtered = this.filterRows(rows, nowMs);

        // A mark on a row that left the list - rotated away, filtered out, evicted - would
        // keep counting towards a delete the user cannot see. Drop it from the selection.
        if (!this.selectedPrefixes.isEmpty()) {
            final Set<String> visible = new HashSet<>();
            for (final NearbyDeviceSighting s : filtered) {
                visible.add(s.getKeyPrefix());
            }
            if (this.selectedPrefixes.retainAll(visible)) {
                this.updateSelectionUi();
            }
        }

        if (filtered.isEmpty()) {
            this.showEmptyState(this.scanUnavailable
                    ? R.string.nearby_devices_scan_unavailable
                    : R.string.nearby_devices_empty);
            return;
        }

        this.binding.nearbyDevicesList.setVisibility(VISIBLE);
        this.binding.nearbyDevicesEmptyState.setVisibility(GONE);
        this.adaptor.setRows(filtered);
        this.adaptor.notifyDataSetChanged();

        this.maybeOfferRotation();
    }

    /**
     * Appends a row for every named prefix the book has no current row for.
     *
     * <p>Naming a tag is a promise to keep listing it: the row is rebuilt from the sighting
     * log's last hearing (or bare prefix if the tag was named before the log recorded one)
     * and lands in the offline tail, which is then re-sorted by last-heard so reconstructed
     * rows interleave with the book's own gone rows instead of always trailing them.
     */
    private void addUnheardNamedRows(
            final List<NearbyDeviceSighting> rows, final long nowMs) {
        final Set<String> shown = new HashSet<>();
        int firstGone = rows.size();
        for (int i = 0; i < rows.size(); i++) {
            shown.add(rows.get(i).getKeyPrefix());
            if (i < firstGone && NearbyDeviceBook.isGone(rows.get(i), nowMs)) {
                firstGone = i;
            }
        }

        final List<NearbyDeviceSighting> unheard = new ArrayList<>();
        for (final Map.Entry<String, String> named : this.names.all().entrySet()) {
            final String prefix = named.getKey();
            if (shown.contains(prefix)) {
                continue;
            }
            final ForeignTagIdLog.Entry record = this.idLog.entry(prefix);
            if (record == null) {
                // Named but never recorded - e.g. the log was cleared or hit its cap. List it
                // anyway: a name with no hearing is still a name. Zero timestamp marks it.
                unheard.add(NearbyDeviceSighting.builder()
                        .keyPrefix(prefix)
                        .seenAtMs(0)
                        .build());
            } else if (record.getRotatedInto() == null) {
                // A rotated-away prefix stays hidden: its name already moved to the
                // successor, and listing both would show one tag twice.
                unheard.add(record.toSighting(prefix));
            }
        }
        if (unheard.isEmpty()) {
            return;
        }
        rows.addAll(unheard);
        rows.subList(firstGone, rows.size())
                .sort(Comparator.comparingLong(NearbyDeviceSighting::getSeenAtMs).reversed());
    }

    /**
     * The rotation question, asked once a plausible pair exists.
     *
     * <p>Runs on the one-second refresh rather than per frame: the heuristic waits for the
     * named prefix to go quiet, and quiet is a thing time decides, not a new advertisement.
     * Shows the closest match - smallest gap between the named prefix's last hearing and the
     * unnamed one's first - because several departed names and several new faces can overlap.
     * Candidates come from the sighting log rather than the visible rows, so a device that
     * arrived and went quiet while the screen was closed is still asked about.
     */
    private void maybeOfferRotation() {
        if (this.suggestedNewPrefix != null) {
            if (this.names.nameFor(this.suggestedNewPrefix) != null) {
                // The hear-path merge answered the question while it was still on
                // screen: the unnamed candidate carries a name now.
                this.hideRotationBanner();
            }
            return; // one question at a time, and it is already on screen
        }

        final long now = System.currentTimeMillis();
        final Map<String, String> names = this.names.all();
        long bestGap = Long.MAX_VALUE;
        String bestOld = null;
        String bestNew = null;

        for (final Map.Entry<String, ForeignTagIdLog.Entry> candidate
                : this.idLog.entries().entrySet()) {
            final String newPrefix = candidate.getKey();
            if (names.containsKey(newPrefix)) {
                continue;
            }
            final ForeignTagIdLog.Entry newEntry = candidate.getValue();
            if (newEntry.getRotatedInto() != null) {
                // A retired identity is dead, not a candidate face.
                continue;
            }

            for (final Map.Entry<String, ForeignTagIdLog.Entry> old
                    : this.idLog.entries().entrySet()) {
                final String oldPrefix = old.getKey();
                if (newPrefix.equals(oldPrefix)
                        || old.getValue().getRotatedInto() != null
                        || !names.containsKey(oldPrefix)
                        || newEntry.hasRejectedPredecessor(oldPrefix)) {
                    continue;
                }
                if (!NearbyRotationHeuristic.couldBeLooseRotation(
                        old.getValue().getLastSeenMs(), old.getValue().getLastRssi(),
                        newEntry.getFirstSeenMs(), newEntry.getLastRssi(), now)) {
                    continue;
                }
                if (!NearbyRotationHeuristic.samePlace(
                        old.getValue().getLastLatitude(), old.getValue().getLastLongitude(),
                        newEntry.getLastLatitude(), newEntry.getLastLongitude())) {
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
            final ForeignTagIdLog.Entry candidate = this.idLog.entry(bestNew);
            final String newDevice = candidate != null
                    && candidate.getLastMac() != null
                    ? candidate.getLastMac()
                    : bestNew.substring(0, 8) + "…";
            this.binding.nearbyRotationText.setText(
                    getString(R.string.nearby_rotation_suggestion,
                            names.get(bestOld), newDevice));
            this.binding.nearbyRotationBanner.setVisibility(VISIBLE);
        }
    }

    private void onRotationConfirmed() {
        if (this.suggestedOldPrefix == null || this.suggestedNewPrefix == null) {
            return;
        }
        ForeignRotation.merge(this.suggestedOldPrefix, this.suggestedNewPrefix,
                this.idLog, this.names);
        this.hideRotationBanner();
        this.rebuildList();
    }

    private void onRotationDismissed() {
        if (this.suggestedNewPrefix != null && this.suggestedOldPrefix != null) {
            this.idLog.rejectRotation(this.suggestedNewPrefix, this.suggestedOldPrefix);
            this.idLog.flush();
        }
        this.hideRotationBanner();
    }

    private void hideRotationBanner() {
        this.suggestedOldPrefix = null;
        this.suggestedNewPrefix = null;
        this.binding.nearbyRotationBanner.setVisibility(GONE);
    }

    /**
     * Starts or stops the presence watch with the switch.
     *
     * <p>Notifications are asked for at the moment they become relevant - the same "ask late,
     * accept a no" contract SettingsActivity uses for the background scan. A refusal leaves
     * the service running invisibly, which is the user's call to make.
     */
    private void onPresenceWatchToggled(final boolean enabled) {
        ForeignTagWatch.setEnabled(this, enabled);
        if (enabled) {
            this.askToShowNotifications();
            ForeignTagScanService.start(this);
        } else {
            ForeignTagScanService.stop(this);
        }
    }

    private void onFilterChanged(final int chipId) {
        if (chipId == R.id.nearby_filter_own) {
            this.filter = NearbyFilter.OWN;
        } else if (chipId == R.id.nearby_filter_online) {
            this.filter = NearbyFilter.ONLINE;
        } else if (chipId == R.id.nearby_filter_named) {
            this.filter = NearbyFilter.NAMED;
        } else if (chipId == R.id.nearby_filter_unnamed) {
            this.filter = NearbyFilter.UNNAMED;
        } else {
            this.filter = NearbyFilter.ALL;
        }
        this.rebuildList();
    }

    /** Applies the selected row filter; ALL is the common path and does no work at all. */
    private List<NearbyDeviceSighting> filterRows(
            final List<NearbyDeviceSighting> rows, final long nowMs) {
        if (this.filter == NearbyFilter.ALL) {
            return rows;
        }
        final List<NearbyDeviceSighting> kept = new ArrayList<>();
        for (final NearbyDeviceSighting s : rows) {
            switch (this.filter) {
                case ONLINE:
                    if (!NearbyDeviceBook.isGone(s, nowMs)) {
                        kept.add(s);
                    }
                    break;
                case OWN:
                    if (this.ownMacs.contains(s.getMac())) {
                        kept.add(s);
                    }
                    break;
                case NAMED:
                    if (this.names.nameFor(s.getKeyPrefix()) != null) {
                        kept.add(s);
                    }
                    break;
                case UNNAMED:
                    if (this.names.nameFor(s.getKeyPrefix()) == null) {
                        kept.add(s);
                    }
                    break;
                default:
                    kept.add(s);
            }
        }
        return kept;
    }

    /**
     * Reloads the derived addresses behind the OWN filter, at most every 30s.
     *
     * <p>Membership over every stored range - not the current index - because the question is
     * "is this MAC one of ours", and an address stays the owner's tag whichever index produced
     * it. Read off the main thread: it is a directory of small files, but the list rebuilds
     * every second and disk has no place in that path.
     */
    private void refreshOwnMacsIfStale() {
        if (System.currentTimeMillis() - this.ownMacsLoadedMs < 30_000) {
            return;
        }
        this.ownMacsLoadedMs = System.currentTimeMillis();
        Observable.fromCallable(() -> this.derivedAddresses.allDerivedAddresses())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(macs -> {
                    this.ownMacs = macs;
                    if (this.filter == NearbyFilter.OWN) {
                        this.rebuildList();
                    }
                }, error -> Log.w(TAG, "Could not read the derived own-tag addresses", error));
    }

    /**
     * Writes names + sighting log as JSON and hands it to a share target.
     *
     * <p>Regenerated per share into the cache dir - the document is scratch space, and only
     * {@code exports/} is reachable through the provider, so nothing else in the app's files
     * is shareable by accident.
     */
    private void shareLog() {
        // Serialising and writing a sighting log of any size is disk work - keep it off the
        // main thread; only the chooser launch has to come back here.
        Observable.fromCallable(() -> {
            final String json =
                    ForeignTagExport.toJson(this.names.all(), this.idLog.entries());
            final File dir = new File(this.getCacheDir(), "exports");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Could not create the export directory");
            }
            final File out = new File(dir, "foreign_tags.json");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return out;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(out -> {
                    final Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("application/json")
                            .putExtra(Intent.EXTRA_STREAM,
                                    FileProvider.getUriForFile(
                                            this,
                                            this.getPackageName() + ".fileprovider", out))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    this.startActivity(Intent.createChooser(
                            send, this.getString(R.string.nearby_export)));
                }, error -> Log.w(TAG, "Could not write the sighting-log export", error));
    }

    private void askToShowNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(
                this, new String[] {Manifest.permission.POST_NOTIFICATIONS},
                NEARBY_PERMISSION_REQUEST_CODE + 1);
    }

    private void showEmptyState(final int textRes) {
        this.binding.nearbyDevicesList.setVisibility(GONE);
        this.binding.nearbyDevicesEmptyState.setVisibility(VISIBLE);
        this.binding.nearbyDevicesEmptyText.setText(textRes);
    }

    /**
     * A click opens the pointing view for this prefix - the radar branch's screen. While the
     * selection bar is up a click marks instead, matching the long-press that opened it.
     */
    private void onDeviceClicked(final NearbyDeviceSighting sighting) {
        if (!this.selectedPrefixes.isEmpty()) {
            this.toggleSelection(sighting);
            return;
        }
        final Intent intent = new Intent(this, NearbyRadarActivity.class);
        intent.putExtra(NearbyRadarActivity.EXTRA_KEY_PREFIX, sighting.getKeyPrefix());
        intent.putExtra(NearbyRadarActivity.EXTRA_MAC, sighting.getMac());
        this.startActivity(intent);
    }

    /** A long-press marks the row; renaming moved onto the selection bar it opens. */
    private void onDeviceLongPressed(final View anchor, final NearbyDeviceSighting sighting) {
        this.toggleSelection(sighting);
    }

    private void toggleSelection(final NearbyDeviceSighting sighting) {
        final String prefix = sighting.getKeyPrefix();
        if (!this.selectedPrefixes.remove(prefix)) {
            this.selectedPrefixes.add(prefix);
        }
        this.updateSelectionUi();
    }

    private void updateSelectionUi() {
        final int count = this.selectedPrefixes.size();
        this.binding.nearbySelectionBar.setVisibility(count > 0 ? VISIBLE : GONE);
        if (count > 0) {
            this.binding.nearbySelectionCount.setText(
                    getString(R.string.nearby_selected_count, count));
            this.binding.nearbySelectionRename.setEnabled(count == 1);
        }
        this.selectionBackCallback.setEnabled(count > 0);
        this.adaptor.setSelectedPrefixes(this.selectedPrefixes);
        this.adaptor.notifyDataSetChanged();
    }

    private void clearSelection() {
        if (this.selectedPrefixes.isEmpty()) {
            return;
        }
        this.selectedPrefixes.clear();
        this.updateSelectionUi();
    }

    /** The bar's rename acts on the single marked row - it is disabled otherwise. */
    private void renameSelected() {
        if (this.selectedPrefixes.size() != 1) {
            return;
        }
        this.showRenameDialog(this.selectedPrefixes.iterator().next());
    }

    /**
     * Deleting forgets the device outright: its name, its sighting-log entry and the row in
     * the book. A prefix still in earshot comes back on its next advertisement - that is a
     * new sighting, not the deleted record, which the confirm dialog says.
     */
    private void confirmDeleteSelected() {
        if (this.selectedPrefixes.isEmpty()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(
                        R.string.nearby_delete_confirm, this.selectedPrefixes.size()))
                .setPositiveButton(R.string.nearby_action_delete, (d, which) -> {
                    for (final String prefix : this.selectedPrefixes) {
                        this.book.remove(prefix);
                        this.names.rename(prefix, null);
                        this.idLog.remove(prefix);
                    }
                    this.idLog.flush();
                    this.selectedPrefixes.clear();
                    this.updateSelectionUi();
                    this.rebuildList();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** The shared foreign-tag name dialog, wired so the list redraws the renamed row. */
    private void showRenameDialog(final String keyPrefix) {
        ForeignTagRename.showDialog(this, this.names, keyPrefix, this::rebuildList);
    }
}
