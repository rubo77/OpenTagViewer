package dev.wander.android.opentagviewer.ui.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.ui.TestHostActivity;

/**
 * The offline banner, drawn in both themes.
 *
 * <p>The strip used to paint itself with {@code md_theme_error_mediumContrast} — a colour role
 * whose night value is a light pink meant for <i>text on a dark surface</i>, not for a
 * background with white text on it. The banner read pink in dark mode. It now uses
 * {@code R.color.offline_banner_bg}, which has no night variant on purpose: the design is the
 * same dark red strip in both modes, and this test is what keeps it that way.
 */
@RunWith(AndroidJUnit4.class)
public class OfflineBannerLayoutTest {

    /** {@code #740006} — the dark red the strip is in light mode, kept for dark mode. */
    private static final int BANNER_BACKGROUND = 0xFF740006;

    @Test
    public void theStripIsDarkRedWithLegibleTextInBothThemes() {
        final TestHostActivity[] host = new TestHostActivity[1];

        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(TestHostActivity.class)) {
            scenario.onActivity(activity -> {
                host[0] = activity;
                showBanner(activity, activity.getLayoutInflater());
            });
            Shot.of(host[0], "offline_banner-day");

            scenario.onActivity(activity -> showBanner(
                    activity,
                    activity.getLayoutInflater().cloneInContext(inNightMode(activity))));
            Shot.of(host[0], "offline_banner-dark");
        }
    }

    private static void showBanner(final TestHostActivity activity, final LayoutInflater inflater) {
        final View banner = inflater.inflate(R.layout.inline_offline_banner, null);

        assertTrue("the strip is a plain colour, not a shaped drawable",
                banner.getBackground() instanceof ColorDrawable);
        assertEquals("the strip is the light-mode dark red in every theme",
                BANNER_BACKGROUND,
                ((ColorDrawable) banner.getBackground()).getColor());

        final TextView text = banner.findViewById(R.id.offline_mode_text);
        assertNotNull("the id the banner code looks up has to resolve", text);
        final int textColour = text.getTextColors().getDefaultColor();
        assertTrue("white text on the strip has to clear WCAG contrast",
                contrastRatio(textColour, BANNER_BACKGROUND) >= 4.5);

        // The layout ships gone - OfflineBanner.apply flips it when a leg is missing, and the
        // strip is what this test is looking at.
        banner.setVisibility(View.VISIBLE);
        activity.setContentView(banner);
    }

    /** WCAG relative-luminance contrast ratio, on sRGB primaries. */
    private static double contrastRatio(final int foreground, final int background) {
        final double l1 = luminance(foreground);
        final double l2 = luminance(background);
        final double light = Math.max(l1, l2);
        final double dark = Math.min(l1, l2);
        return (light + 0.05) / (dark + 0.05);
    }

    private static double luminance(final int colour) {
        return 0.2126 * channel((colour >> 16) & 0xFF)
                + 0.7152 * channel((colour >> 8) & 0xFF)
                + 0.0722 * channel(colour & 0xFF);
    }

    private static double channel(final int value) {
        final double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** The app's theme, in a configuration that says night, without changing the device. */
    private static Context inNightMode(final Context base) {
        final Configuration night = new Configuration(base.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | Configuration.UI_MODE_NIGHT_YES;

        return new ContextThemeWrapper(
                base.createConfigurationContext(night), R.style.Theme_OpenTagViewer);
    }
}
