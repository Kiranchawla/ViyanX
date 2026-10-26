package com.example.viyanx;

import android.content.Context;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

/** Shared inset handling for edge-to-edge screens. */
final class SystemInsets {

    private static final int TABLET_MIN_WIDTH_DP = 600;
    // Some tablet taskbars draw taller than the inset they report, hiding the bottom of the app.
    private static final int TABLET_MIN_BOTTOM_DP = 48;

    private SystemInsets() {}

    /** Status bar, navigation bar and cutout insets, with a safe minimum bottom on tablets. */
    static Insets safe(Context context, WindowInsetsCompat insets) {
        Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        if (context.getResources().getConfiguration().smallestScreenWidthDp
                >= TABLET_MIN_WIDTH_DP) {
            int min = Math.round(TABLET_MIN_BOTTOM_DP
                    * context.getResources().getDisplayMetrics().density);
            return Insets.of(bars.left, bars.top, bars.right, Math.max(bars.bottom, min));
        }
        return bars;
    }
}
