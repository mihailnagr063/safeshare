package dev.medveed.safeshare.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.medveed.safeshare.BuildConfig;
import dev.medveed.safeshare.R;
import dev.medveed.safeshare.net.ApiClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SettingsFragment extends Fragment {

    private TextInputEditText editServerUrl;
    private TextView textHealth;
    private TextView textVersion;
    private ExecutorService executor;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        editServerUrl = v.findViewById(R.id.edit_server_url);
        textHealth = v.findViewById(R.id.text_health);
        textVersion = v.findViewById(R.id.text_version);
        MaterialButton save = v.findViewById(R.id.button_save);
        MaterialButton reset = v.findViewById(R.id.button_reset);
        MaterialButton check = v.findViewById(R.id.button_health);

        editServerUrl.setText(ApiClient.get(requireContext()).baseUrl());
        textVersion.setText(getString(R.string.settings_version_fmt,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        save.setOnClickListener(x -> saveUrl());
        reset.setOnClickListener(x -> resetUrl());
        check.setOnClickListener(x -> probeHealth());
    }

    private void saveUrl() {
        CharSequence cs = editServerUrl.getText();
        if (cs == null) return;
        String url = cs.toString().trim();
        if (url.isEmpty()) {
            editServerUrl.setError(getString(R.string.settings_url_empty));
            return;
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            editServerUrl.setError(getString(R.string.settings_url_bad_scheme));
            return;
        }
        ApiClient.setBaseUrl(requireContext(), url);
        editServerUrl.setText(ApiClient.get(requireContext()).baseUrl());
        Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();
    }

    private void resetUrl() {
        ApiClient.setBaseUrl(requireContext(), "");
        editServerUrl.setText(ApiClient.get(requireContext()).baseUrl());
        Toast.makeText(requireContext(), R.string.settings_reset_done, Toast.LENGTH_SHORT).show();
    }

    private void probeHealth() {
        textHealth.setText(R.string.settings_checking);
        final String base = ApiClient.get(requireContext()).baseUrl();
        executor.execute(() -> {
            String result;
            try {
                Request req = new Request.Builder().url(base + "healthz").build();
                try (Response resp = new OkHttpClient().newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        result = getString(R.string.settings_server_ok);
                    } else {
                        result = getString(R.string.settings_server_bad_code,
                                resp.code());
                    }
                }
            } catch (IOException e) {
                result = getString(R.string.settings_server_unreachable,
                        e.getMessage() == null ? "?" : e.getMessage());
            }
            final String r = result;
            if (textHealth != null) {
                textHealth.post(() -> textHealth.setText(r));
            }
        });
    }
}
