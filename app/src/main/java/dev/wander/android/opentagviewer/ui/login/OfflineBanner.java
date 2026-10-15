package dev.wander.android.opentagviewer.ui.login;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import android.os.Build;

import dev.wander.android.opentagviewer.AppleLoginActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The "something is off" strip, shared by every screen whose function needs an account
 * or the radio.
 *
 * <p>Reads the raw {@code APPLE_ACCOUNT} preference rather than
 * {@code UserAuthRepository.getUserAuth}: presence of the record is the whole question,
 * and decrypting it on every emission just to throw the body away would buy nothing.
 * Bluetooth state is a broadcast, so the strip reacts the moment the user toggles it in
 * the quick settings - an app that cannot enable Bluetooth itself (no permission on this
 * path) can at least say plainly that it sees it is off.
 *
 * <p>Both-off draws full signal red, either-alone the dark red: the saturated strip is the
 * more alarming one because then nothing on the screen works at all. A datastore read error is
 * logged and treated as signed-in - claiming a user is signed out on a failure would be
 * worse than silence.
 */
public final class OfflineBanner {

    private static final String TAG = OfflineBanner.class.getSimpleName();

    private OfflineBanner() {}

    private enum Mode { NO_ACCOUNT, NO_BLUETOOTH, NEITHER }

    /**
     * Wires the banner's buttons and keeps it bound to account presence and the Bluetooth
     * radio state. The caller keeps the returned subscription for its own lifecycle dispose.
     */
    public static Disposable watch(final Activity activity, final View banner) {
        final TextView text = banner.findViewById(R.id.offline_mode_text);
        final Button signIn = banner.findViewById(R.id.offline_mode_sign_in);
        final Button enableBt = banner.findViewById(R.id.offline_mode_enable_bt);

        signIn.setOnClickListener(v -> activity.startActivity(
                new Intent(activity, AppleLoginActivity.class)));
        enableBt.setOnClickListener(v -> activity.startActivity(enableBluetoothIntent(activity)));

        final Observable<Boolean> signedIn = UserAuthDataStore
                .getInstance(activity.getApplicationContext())
                .data()
                .map(prefs -> prefs.get(UserAuthDataStore.APPLE_ACCOUNT) != null)
                .onErrorReturn(error -> {
                    Log.e(TAG, "Could not read account presence", error);
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.io())
                .distinctUntilChanged()
                .toObservable();

        final Observable<Boolean> bluetoothOn = bluetoothEnabled(activity);

        return Observable.combineLatest(signedIn, bluetoothOn,
                        (in, bt) -> !in && !bt ? Mode.NEITHER
                                : !in ? Mode.NO_ACCOUNT
                                : !bt ? Mode.NO_BLUETOOTH
                                : null)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(mode -> {
                    if (mode == null) {
                        banner.setVisibility(View.GONE);
                        return;
                    }
                    apply(activity, banner, text, signIn, enableBt, mode);
                }, error -> Log.e(TAG, "Offline banner watch failed", error));
    }

    /**
     * The adapter's enabled state as it changes. Emits once on subscribe, then per
     * {@code ACTION_STATE_CHANGED}; completes when the device has no Bluetooth at all,
     * because then the banner is not the thing that is missing.
     */
    private static Observable<Boolean> bluetoothEnabled(final Activity activity) {
        return Observable.<Boolean>create(emitter -> {
            final BluetoothManager manager =
                    activity.getSystemService(BluetoothManager.class);
            final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) {
                emitter.onNext(Boolean.TRUE); // no radio: nothing here can fix or lose it
                emitter.onComplete();
                return;
            }
            emitter.onNext(adapter.isEnabled());
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    emitter.onNext(adapter.isEnabled());
                }
            };
            emitter.setCancellable(() -> activity.unregisterReceiver(receiver));
            activity.registerReceiver(receiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        }).subscribeOn(AndroidSchedulers.mainThread()).distinctUntilChanged();
    }

    /**
     * {@code ACTION_REQUEST_ENABLE} shows the in-place system dialog but wants
     * {@code BLUETOOTH_CONNECT} on Android 12+; before S the permission does not exist, and
     * without the grant the Bluetooth settings screen is the honest fallback.
     */
    private static Intent enableBluetoothIntent(final Activity activity) {
        final boolean needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(activity,
                        android.Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED;
        return needsGrant
                ? new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                : new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
    }

    private static void apply(final Context context, final View banner, final TextView text,
                              final Button signIn, final Button enableBt, final Mode mode) {
        final boolean both = mode == Mode.NEITHER;
        banner.setBackgroundColor(both
                ? Color.RED
                : ContextCompat.getColor(context, R.color.offline_banner_bg));
        final int textColor = ContextCompat.getColor(context,
                both ? android.R.color.black : android.R.color.white);
        text.setTextColor(textColor);
        signIn.setTextColor(textColor);
        enableBt.setTextColor(textColor);

        text.setText(both ? R.string.offline_mode_banner_nothing
                : mode == Mode.NO_ACCOUNT ? R.string.offline_mode_banner
                : R.string.offline_mode_banner_no_bluetooth);
        signIn.setVisibility(mode == Mode.NO_BLUETOOTH ? View.GONE : View.VISIBLE);
        enableBt.setVisibility(mode == Mode.NO_ACCOUNT ? View.GONE : View.VISIBLE);
        banner.setVisibility(View.VISIBLE);
    }
}
