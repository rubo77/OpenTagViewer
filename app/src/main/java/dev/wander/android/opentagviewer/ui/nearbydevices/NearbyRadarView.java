package dev.wander.android.opentagviewer.ui.nearbydevices;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.util.nearby.RadarFeedback;

/**
 * The circular sweep in the middle of the radar screen.
 *
 * <p><b>Dumb by design.</b> It knows three things - a rotating sweep line, a distance the
 * tracked device was last heard at, and whether it is heard at all - and redraws them. Which
 * device, how distance was estimated, and when to beep all live in the activity, because a
 * view that owns a radio scan cannot be paused correctly by anything else.
 *
 * <p>The sweep is cosmetic, not a direction indicator: a single BLE radio cannot say where a
 * signal came from, only how strong it was. The rotation exists so the eye has something alive
 * to track while walking; the honest part is the blip's radius, which is the distance estimate.
 */
public class NearbyRadarView extends View {

    /** Concentric distance rings; the outermost is the {@code RANGE_TOP_M} edge. */
    private static final int RING_COUNT = 4;

    /** Sweep speed, degrees per rendered frame at ~60fps - about two seconds a revolution. */
    private static final float SWEEP_STEP_DEGREES = 3f;

    /** The distance the outermost ring stands for - the same edge the beeps saturate at. */
    private static final float RANGE_TOP_M = (float) RadarFeedback.RANGE_TOP_M;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float sweepDegrees = 0f;

    /** Distance the blip sits at, in metres; NaN while nothing has been heard. */
    private float deviceDistanceM = Float.NaN;

    /** Whether the sweep spins at all - stopped while the device is unheard. */
    private boolean tracking = false;

    private final Runnable animate = new Runnable() {
        @Override
        public void run() {
            if (tracking) {
                sweepDegrees = (sweepDegrees + SWEEP_STEP_DEGREES) % 360f;
            }
            invalidate();
        }
    };

    public NearbyRadarView(final Context context) {
        this(context, null);
    }

    public NearbyRadarView(final Context context, @Nullable final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NearbyRadarView(
            final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        this.ringPaint.setStyle(Paint.Style.STROKE);
        this.ringPaint.setStrokeWidth(dp(1));
        this.ringPaint.setColor(resolveColor(context, com.google.android.material.R.attr.colorOutline));

        this.sweepPaint.setStyle(Paint.Style.STROKE);
        this.sweepPaint.setStrokeWidth(dp(2));
        this.sweepPaint.setStrokeCap(Paint.Cap.ROUND);
        this.sweepPaint.setColor(resolveColor(context, com.google.android.material.R.attr.colorPrimary));

        this.blipPaint.setStyle(Paint.Style.FILL);
        this.blipPaint.setColor(resolveColor(context, com.google.android.material.R.attr.colorPrimary));

        this.centerPaint.setStyle(Paint.Style.FILL);
        this.centerPaint.setColor(resolveColor(context, com.google.android.material.R.attr.colorOnSurface));
    }

    /**
     * Where the tracked device was last heard, in metres; {@link Float#NaN} hides the blip.
     *
     * <p>{@code tracking} stops the sweep when false - a device that went quiet freezes the
     * display rather than pretending the hunt continues.
     */
    public void updateDevice(final float distanceMeters, final boolean tracking) {
        this.deviceDistanceM = distanceMeters;
        this.tracking = tracking;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        this.postOnAnimation(this.animate);
    }

    @Override
    protected void onDetachedFromWindow() {
        this.removeCallbacks(this.animate);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float radius = Math.min(cx, cy) - dp(8);
        if (radius <= 0) {
            return;
        }

        for (int i = 1; i <= RING_COUNT; i++) {
            canvas.drawCircle(cx, cy, radius * i / RING_COUNT, this.ringPaint);
        }

        // The rotating line: drawn along the sweep angle through the full radius.
        final float rad = (float) Math.toRadians(this.sweepDegrees);
        canvas.drawLine(cx, cy,
                cx + radius * (float) Math.cos(rad),
                cy + radius * (float) Math.sin(rad),
                this.sweepPaint);

        canvas.drawCircle(cx, cy, dp(4), this.centerPaint);

        if (!Float.isNaN(this.deviceDistanceM)) {
            final float fraction =
                    Math.min(this.deviceDistanceM / RANGE_TOP_M, 1f);
            canvas.drawCircle(
                    cx + radius * fraction * (float) Math.cos(rad),
                    cy + radius * fraction * (float) Math.sin(rad),
                    dp(7), this.blipPaint);
        }

        if (this.isAttachedToWindow()) {
            this.postOnAnimation(this.animate);
        }
    }

    private float dp(final float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static int resolveColor(final Context context, final int attr) {
        final TypedArray a = context.getTheme().obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, ContextCompat.getColor(context, R.color.black));
        } finally {
            a.recycle();
        }
    }
}
