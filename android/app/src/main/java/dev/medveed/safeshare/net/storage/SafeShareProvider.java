package dev.medveed.safeshare.net.storage;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.io.InputStream;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.crypto.KeyMaterial;
import dev.medveed.safeshare.net.ApiClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class SafeShareProvider implements StorageProvider {

    private final OkHttpClient client = new OkHttpClient();

    @Override
    public String prefix() { return "s"; }

    @Override
    public String displayName() { return "SafeShare"; }

    @Override
    public String configId() { return null; }

    @Override
    public boolean isConfigured(Context ctx) { return true; }

    @Override
    public void disconnect(Context ctx) {}

    @Override @Nullable
    public String upload(Context ctx, InputStream plaintextIn, String name,
                         long plaintextSize, KeyMaterial km,
                         @Nullable ProgressListener progress) {
        return null;
    }

    @Override
    public long contentLength(Context ctx, String publicUrl) { return -1; }

    @Override @Nullable
    public byte[] download(Context ctx, String publicUrl, @Nullable ProgressListener progress) {
        return null;
    }

    @Override
    public void delete(Context ctx, String publicUrl) {}

    @Override
    public void refreshView(View v, Context ctx) {
        TextInputEditText editUrl = v.findViewById(R.id.edit_safeshare_url);
        if (editUrl != null) editUrl.setText(ApiClient.get(ctx).baseUrl());
    }

    @Override
    public View getSetupView(LayoutInflater inflater, ViewGroup parent, Context ctx) {
        View v = inflater.inflate(R.layout.view_provider_safeshare, parent, false);

        TextInputEditText editUrl = v.findViewById(R.id.edit_safeshare_url);
        MaterialButton save = v.findViewById(R.id.button_safeshare_save);
        MaterialButton reset = v.findViewById(R.id.button_safeshare_reset);
        TextView health = v.findViewById(R.id.text_safeshare_health);
        MaterialButton check = v.findViewById(R.id.button_safeshare_check);

        editUrl.setText(ApiClient.get(ctx).baseUrl());

        save.setOnClickListener(x -> {
            CharSequence cs = editUrl.getText();
            if (cs == null) return;
            String url = cs.toString().trim();
            if (url.isEmpty()) {
                editUrl.setError(ctx.getString(R.string.settings_url_empty));
                return;
            }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                editUrl.setError(ctx.getString(R.string.settings_url_bad_scheme));
                return;
            }
            ApiClient.setBaseUrl(ctx, url);
            editUrl.setText(ApiClient.get(ctx).baseUrl());
        });

        reset.setOnClickListener(x -> {
            ApiClient.setBaseUrl(ctx, "");
            editUrl.setText(ApiClient.get(ctx).baseUrl());
        });

        check.setOnClickListener(x -> {
            health.setText(R.string.settings_checking);
            final String base = ApiClient.get(ctx).baseUrl();
            new Thread(() -> {
                String result;
                try {
                    Request req = new Request.Builder().url(base + "healthz").build();
                    try (Response resp = client.newCall(req).execute()) {
                        result = resp.isSuccessful()
                                ? ctx.getString(R.string.settings_server_ok)
                                : ctx.getString(R.string.settings_server_bad_code, resp.code());
                    }
                } catch (IOException e) {
                    result = ctx.getString(R.string.settings_server_unreachable,
                            e.getMessage() == null ? "?" : e.getMessage());
                }
                final String r = result;
                health.post(() -> health.setText(r));
            }).start();
        });

        return v;
    }
}
