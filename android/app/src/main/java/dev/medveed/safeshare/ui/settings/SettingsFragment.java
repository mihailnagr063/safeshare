package dev.medveed.safeshare.ui.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import dev.medveed.safeshare.util.SnackbarUtil;

import dev.medveed.safeshare.BuildConfig;
import dev.medveed.safeshare.R;
import dev.medveed.safeshare.db.AppDatabase;

public class SettingsFragment extends Fragment {

    private MaterialButtonToggleGroup groupTheme;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        groupTheme = v.findViewById(R.id.group_theme);
        TextView textVersion = v.findViewById(R.id.text_version);
        MaterialButton clearHistory = v.findViewById(R.id.button_clear_history);
        MaterialButton resetOnboarding = v.findViewById(R.id.button_reset_onboarding);
        View buttonStorage = v.findViewById(R.id.button_storage);

        View groupDebug = v.findViewById(R.id.group_debug);

        textVersion.setText(getString(R.string.settings_version_fmt,
                BuildConfig.VERSION_NAME));

        groupDebug.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);

        loadTheme();

        clearHistory.setOnClickListener(x -> confirmClearHistory());
        resetOnboarding.setOnClickListener(x -> resetOnboarding());
        buttonStorage.setOnClickListener(x -> openStorageSettings());

        groupTheme.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            int mode;
            if (id == R.id.theme_light) mode = AppCompatDelegate.MODE_NIGHT_NO;
            else if (id == R.id.theme_dark) mode = AppCompatDelegate.MODE_NIGHT_YES;
            else mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            AppCompatDelegate.setDefaultNightMode(mode);
            requireContext().getSharedPreferences("settings", 0)
                    .edit().putInt("theme_mode", mode).apply();
        });
    }

    private void openStorageSettings() {
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left,
                        R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(R.id.nav_host, new StorageSettingsFragment())
                .addToBackStack("storage")
                .commit();
    }

    private void loadTheme() {
        int saved = requireContext().getSharedPreferences("settings", 0)
                .getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        int id;
        if (saved == AppCompatDelegate.MODE_NIGHT_NO) id = R.id.theme_light;
        else if (saved == AppCompatDelegate.MODE_NIGHT_YES) id = R.id.theme_dark;
        else id = R.id.theme_system;
        groupTheme.check(id);
    }

    private void confirmClearHistory() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_confirm)
                .setPositiveButton(R.string.history_clear, (dialog, which) -> {
                    Context ctx = requireContext();
                    new Thread(() -> {
                        AppDatabase.get(ctx).transferDao().deleteAll();
                        if (getView() != null)
                            getView().post(() ->
                                    SnackbarUtil.show(SettingsFragment.this, getView(), R.string.history_cleared));
                    }).start();
                })
                .setNegativeButton(R.string.contacts_cancel, null)
                .show();
    }

    private void resetOnboarding() {
        requireContext().getSharedPreferences("onboarding", 0)
                .edit().putBoolean("done", false).apply();
        requireContext().getSharedPreferences("discovery", 0)
                .edit().putBoolean("done", false).apply();
        if (getView() != null)
            SnackbarUtil.show(this, getView(), R.string.settings_onboarding_reset);
    }
}
