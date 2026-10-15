package dev.wander.android.opentagviewer.ui.nearbydevices;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement;
import dev.wander.android.opentagviewer.ble.NearbyDeviceBook;
import dev.wander.android.opentagviewer.ble.NearbyDeviceSighting;
import dev.wander.android.opentagviewer.util.nearby.ProximityDistance;
import lombok.Getter;
import java.util.function.Function;

/**
 * The rows of the nearby-devices list: one per key prefix, name-or-prefix on top.
 *
 * <p>Backed by a snapshot list the activity swaps wholesale rather than by the book itself -
 * RecyclerView wants positions stable within a bind pass, and the book mutates on the scan
 * thread, so the activity hands over an immutable copy and only then notifies.
 */
public class NearbyDeviceListAdaptor
        extends RecyclerView.Adapter<NearbyDeviceListAdaptor.ViewHolder> {

    /**
     * Opacity for a row whose device stopped advertising: dimmed but still readable -
     * {@code colorControlHighlight}-like greys would fail contrast, so the whole row
     * including icon and text fades to roughly half instead.
     */
    private static final float GONE_ROW_ALPHA = 0.5f;

    /** Click: select the device (future radar). Long click: rename - carries the row view as anchor. */
    private final Consumer<NearbyDeviceSighting> onClickCallback;
    private final BiConsumer<View, NearbyDeviceSighting> onLongClickCallback;

    /** prefix → user-given name; consulted on every bind so a rename shows on the next pass. */
    private final Function<String, String> nameResolver;

    private final Resources resources;

    private final List<NearbyDeviceSighting> rows = new ArrayList<>();

    /**
     * Prefixes marked in the selection mode. Empty means plain browsing - the check icon is
     * gone and clicks navigate, so the set doubles as the mode switch.
     */
    private final Set<String> selectedPrefixes = new HashSet<>();

    /** The ripple a row started with, resolved once so a deselected row gets it back. */
    private int defaultRowBackground = -1;

    @Getter
    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final FrameLayout container;
        private final TextView deviceName;
        private final TextView details;
        private final TextView mac;
        private final ImageView itemCheck;

        public ViewHolder(@NonNull final View itemView) {
            super(itemView);
            this.container = itemView.findViewById(R.id.nearby_device_item_container);
            this.deviceName = itemView.findViewById(R.id.nearby_item_device_name);
            this.details = itemView.findViewById(R.id.nearby_item_details);
            this.mac = itemView.findViewById(R.id.nearby_item_mac);
            this.itemCheck = itemView.findViewById(R.id.nearby_item_check);
        }
    }

    public NearbyDeviceListAdaptor(
            @lombok.NonNull final Resources resources,
            @lombok.NonNull final Function<String, String> nameResolver,
            @lombok.NonNull final Consumer<NearbyDeviceSighting> onClickCallback,
            @lombok.NonNull final BiConsumer<View, NearbyDeviceSighting> onLongClickCallback) {
        this.resources = resources;
        this.nameResolver = nameResolver;
        this.onClickCallback = onClickCallback;
        this.onLongClickCallback = onLongClickCallback;
    }

    /** Replaces the whole snapshot; the caller notifies. */
    public void setRows(final List<NearbyDeviceSighting> sightings) {
        this.rows.clear();
        this.rows.addAll(sightings);
    }

    /** Replaces the marked prefixes; an empty set turns the selection chrome off again. */
    public void setSelectedPrefixes(final Set<String> selected) {
        this.selectedPrefixes.clear();
        this.selectedPrefixes.addAll(selected);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.nearby_device_list_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final NearbyDeviceSighting sighting = this.rows.get(position);
        final boolean gone = NearbyDeviceBook.isGone(sighting, System.currentTimeMillis());

        final String givenName = this.nameResolver.apply(sighting.getKeyPrefix());
        final String title = givenName != null
                ? givenName
                : this.resources.getString(R.string.nearby_device_unnamed,
                        sighting.getKeyPrefix().substring(0, 4));
        holder.getDeviceName().setText(title);

        final List<String> parts = new ArrayList<>();
        if (gone) {
            // Distance, battery and owner-state would claim conditions from before the tag
            // left; the row reports only that it went quiet and when it was last heard.
            parts.add(this.resources.getString(R.string.nearby_device_offline));
        } else {
            // The details line is assembled from localized pieces: distance, battery,
            // owner-state, then how stale the newest hearing is.
            parts.add(this.resources.getString(R.string.nearby_device_distance_format,
                    ProximityDistance.describe(sighting.getRssi())));
            if (sighting.getBatteryLevel() != null) {
                parts.add(this.resources.getString(batteryString(sighting.getBatteryLevel())));
            }
            if (sighting.getState() != null) {
                parts.add(this.resources.getString(
                        sighting.getState() == FindMyAdvertisement.State.SEPARATED
                                ? R.string.nearby_device_separated
                                : R.string.nearby_device_with_owner));
            }
        }
        if (sighting.getSeenAtMs() > 0) {
            parts.add(DateUtils.getRelativeTimeSpanString(
                    sighting.getSeenAtMs(), System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS).toString());
        }
        holder.getDetails().setText(String.join(" · ", parts));

        holder.getMac().setText(sighting.getMac());

        // Rows are recycled, so the dimming has to be set unconditionally on every bind.
        holder.getContainer().setAlpha(gone ? GONE_ROW_ALPHA : 1.0f);

        // Selection chrome: while any row is marked every row carries the check icon, the
        // marked ones filled and tinted; the marked row also swaps its ripple for the
        // secondary container colour. Both sides are set unconditionally - rows recycle.
        final boolean selecting = !this.selectedPrefixes.isEmpty();
        final boolean selected = this.selectedPrefixes.contains(sighting.getKeyPrefix());
        holder.getItemCheck().setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            holder.getItemCheck().setImageResource(selected
                    ? R.drawable.check_circle_24px_fill
                    : R.drawable.check_circle_24px);
            holder.getItemCheck().setImageTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(holder.itemView, selected
                            ? com.google.android.material.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorOutline)));
        }
        if (selected) {
            holder.getContainer().setBackgroundColor(MaterialColors.getColor(holder.itemView,
                    com.google.android.material.R.attr.colorSecondaryContainer));
        } else {
            if (this.defaultRowBackground < 0) {
                final TypedValue value = new TypedValue();
                holder.itemView.getContext().getTheme().resolveAttribute(
                        android.R.attr.selectableItemBackground, value, true);
                this.defaultRowBackground = value.resourceId;
            }
            holder.getContainer().setBackgroundResource(this.defaultRowBackground);
        }

        holder.getContainer().setOnClickListener(v -> this.onClickCallback.accept(sighting));
        holder.getContainer().setOnLongClickListener(v -> {
            this.onLongClickCallback.accept(v, sighting);
            return true;
        });
    }

    private static int batteryString(final FindMyAdvertisement.BatteryLevel level) {
        switch (level) {
            case MEDIUM: return R.string.battery_short_medium;
            case LOW: return R.string.battery_short_low;
            case VERY_LOW: return R.string.battery_short_very_low;
            case FULL:
            default: return R.string.battery_short_full;
        }
    }

    @Override
    public int getItemCount() {
        return this.rows.size();
    }
}
