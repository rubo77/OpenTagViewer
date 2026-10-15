package dev.wander.android.opentagviewer.ui.nearbydevices;

import android.content.Context;
import android.view.View;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Objects;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.ble.ForeignTagNames;

/**
 * The rename dialog for a foreign Find My device, shared by the nearby list and the radar.
 *
 * <p>The same name dialog the tag page uses, pointed at a prefix instead of a naming record:
 * a name for a foreign tag lives only in this app, on the only identity it broadcasts.
 */
public final class ForeignTagRename {

    private ForeignTagRename() {}

    /**
     * Shows the dialog and runs {@code after} once a name was committed, so the caller can
     * redraw whatever showed the old one.
     */
    public static void showDialog(final Context context, final ForeignTagNames names,
                                  final String keyPrefix, final Runnable after) {
        final View view = View.inflate(context, R.layout.edit_device_name_dialog, null);
        final TextInputEditText textInput = view.findViewById(R.id.device_name_input);
        final String existing = names.nameFor(keyPrefix);
        if (existing != null) {
            textInput.setText(existing);
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.device_name)
                .setIcon(R.drawable.edit_24px)
                .setView(view)
                .setPositiveButton(R.string.confirm, (d, which) -> {
                    names.rename(keyPrefix,
                            Objects.requireNonNull(textInput.getText()).toString());
                    after.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();

        // No empty-field guard here, unlike the tag page's copy: a foreign tag has no upstream
        // name to overwrite, so a blank field is the only "unnamed" there is - confirm clears.
    }
}
