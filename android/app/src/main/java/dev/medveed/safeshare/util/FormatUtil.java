package dev.medveed.safeshare.util;

import java.util.Locale;

public final class FormatUtil {

    private FormatUtil() {}

    public static String humanSize(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
