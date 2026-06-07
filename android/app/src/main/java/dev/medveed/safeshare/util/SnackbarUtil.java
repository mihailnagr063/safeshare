package dev.medveed.safeshare.util;

import android.view.View;

import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

import dev.medveed.safeshare.R;

public final class SnackbarUtil {

    private SnackbarUtil() {}

    public static void show(Fragment fragment, View view, @StringRes int resId) {
        show(fragment, view, fragment.getString(resId), Snackbar.LENGTH_SHORT);
    }

    public static void show(Fragment fragment, View view, @StringRes int resId, int duration) {
        show(fragment, view, fragment.getString(resId), duration);
    }

    public static void show(Fragment fragment, View view, CharSequence text) {
        show(fragment, view, text, Snackbar.LENGTH_SHORT);
    }

    public static void show(Fragment fragment, View view, CharSequence text, int duration) {
        Snackbar sb = Snackbar.make(view, text, duration);
        View bottomNav = fragment.requireActivity().findViewById(R.id.bottom_nav);
        if (bottomNav != null) {
            sb.setAnchorView(bottomNav);
        }
        sb.show();
    }
}
