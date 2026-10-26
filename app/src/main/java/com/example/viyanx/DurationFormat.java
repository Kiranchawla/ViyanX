package com.example.viyanx;

import java.util.Locale;

/** Formats a length in milliseconds as m:ss, or h:mm:ss for an hour or more. */
final class DurationFormat {

    private DurationFormat() {}

    static String format(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /** Like {@link #format(long)} with tenths of a second, such as 0:04.2, for precise trimming. */
    static String formatPrecise(long millis) {
        long clamped = Math.max(0, millis);
        long tenths = (clamped % 1000) / 100;
        return format(clamped) + "." + tenths;
    }
}
