package dev.medveed.safeshare.net.storage;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Locale;

import dev.medveed.safeshare.BuildConfig;
import dev.medveed.safeshare.R;
import dev.medveed.safeshare.crypto.KeyMaterial;
import dev.medveed.safeshare.crypto.StreamingAesGcm;
import dev.medveed.safeshare.ui.oauth.OAuthWebViewActivity;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import okio.BufferedSink;

public final class YandexDiskProvider implements StorageProvider {

    private static final String TAG = "YandexDisk";
    private static final String API_BASE = "https://cloud-api.yandex.net/v1/disk";

    private final OkHttpClient client;

    {
        OkHttpClient.Builder b = new OkHttpClient.Builder();
        if (BuildConfig.DEBUG) {
            b.addInterceptor(new HttpLoggingInterceptor()
                    .setLevel(HttpLoggingInterceptor.Level.HEADERS));
        }
        client = b.build();
    }

    @Override public String prefix() { return "y"; }
    @Override public String displayName() { return "Яндекс.Диск"; }
    @Override public String configId() { return "yandex_disk"; }

    private SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(configId(), 0);
    }

    @Override
    public boolean isConfigured(Context ctx) {
        return prefs(ctx).getString("access_token", null) != null;
    }

    @Override
    public void disconnect(Context ctx) {
        prefs(ctx).edit().remove("access_token").apply();
    }

    @Override
    public void refreshView(View v, Context ctx) {
        initBodyState(v, ctx);
    }

    private void initBodyState(View v, Context ctx) {
        MaterialButton auth = v.findViewById(R.id.button_yandex_auth);
        MaterialButton disconnect = v.findViewById(R.id.button_yandex_disconnect);
        TextView userText = v.findViewById(R.id.text_yandex_user);
        TextView spaceText = v.findViewById(R.id.text_yandex_space);
        LinearProgressIndicator progress = v.findViewById(R.id.progress_yandex_space);
        TextInputEditText editToken = v.findViewById(R.id.edit_yandex_token);

        String token = prefs(ctx).getString("access_token", null);
        boolean connected = token != null;

        auth.setEnabled(!connected);
        disconnect.setEnabled(connected);

        if (connected) {
            fetchDiskInfo(token, ctx, userText, spaceText, progress);
            if (BuildConfig.DEBUG) editToken.setText(token);
        } else {
            userText.setVisibility(View.GONE);
            spaceText.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
        }

        updateCardHeader(v, ctx, connected);
    }

    private void updateCardHeader(View bodyView, Context ctx, boolean connected) {
        View card = (View) bodyView.getParent();
        while (card != null && !(card instanceof MaterialCardView)) {
            card = (View) card.getParent();
        }
        if (card == null) return;
        ImageView icon = card.findViewById(R.id.icon_status);
        TextView text = card.findViewById(R.id.text_status);
        if (icon != null) {
            icon.setImageResource(connected
                    ? R.drawable.ic_check_circle : R.drawable.ic_cancel_circle);
            icon.setColorFilter(connected ? 0xFF4CAF50 : 0xFF9E9E9E, PorterDuff.Mode.SRC_IN);
        }
        if (text != null) {
            text.setText(connected ? R.string.status_connected : R.string.status_disconnected);
        }
    }

    @Override @Nullable
    public String upload(Context ctx, InputStream plaintextIn, String name,
                         long plaintextSize, KeyMaterial km,
                         @Nullable ProgressListener progress) throws Exception {
        String token = prefs(ctx).getString("access_token", null);
        if (token == null) throw new IllegalStateException("Yandex Disk not configured");

        String encPath = Uri.encode("app:/" + name);

        String uploadUrl;
        {
            Request req = new Request.Builder()
                    .url(API_BASE + "/resources/upload?path=" + encPath + "&overwrite=true")
                    .header("Authorization", "OAuth " + token)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful())
                    throw new IOException("get upload url failed: HTTP " + resp.code());
                uploadUrl = new JSONObject(resp.body().string()).getString("href");
            }
        }

        long cipherSize = StreamingAesGcm.ciphertextLength(name, plaintextSize);

        {
            RequestBody body = new RequestBody() {
                @Override public MediaType contentType() {
                    return MediaType.get("application/octet-stream");
                }
                @Override public long contentLength() {
                    return cipherSize;
                }
                @Override public void writeTo(BufferedSink sink) throws IOException {
                    InputStream counter = new CountingInputStream(plaintextIn, plaintextSize, progress);
                    try {
                        StreamingAesGcm.encrypt(km, name, plaintextSize, counter, sink.outputStream());
                    } catch (GeneralSecurityException e) {
                        throw new IOException("encryption failed", e);
                    }
                }
            };
            Request req = new Request.Builder()
                    .url(uploadUrl)
                    .put(body)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful())
                    throw new IOException("upload blob failed: HTTP " + resp.code());
            }
        }

        {
            Request req = new Request.Builder()
                    .url(API_BASE + "/resources/publish?path=" + encPath)
                    .header("Authorization", "OAuth " + token)
                    .put(RequestBody.create(null, new byte[0]))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful())
                    throw new IOException("publish failed: HTTP " + resp.code());
            }
        }

        String publicUrl;
        {
            Request req = new Request.Builder()
                    .url(API_BASE + "/resources?path=" + encPath + "&fields=public_url")
                    .header("Authorization", "OAuth " + token)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful())
                    throw new IOException("get public url failed: HTTP " + resp.code());
                publicUrl = new JSONObject(resp.body().string()).getString("public_url");
            }
        }

        return publicUrl;
    }

    @Override
    public long contentLength(Context ctx, String publicUrl) throws Exception {
        String realUrl = resolveDownloadUrl(publicUrl);
        Request req = new Request.Builder().url(realUrl).head().build();
        try (Response resp = client.newCall(req).execute()) {
            return resp.body() != null ? resp.body().contentLength() : -1;
        }
    }

    @Override
    public byte[] download(Context ctx, String publicUrl,
                           @Nullable ProgressListener progress) throws Exception {
        String realUrl = resolveDownloadUrl(publicUrl);
        Request req = new Request.Builder().url(realUrl).build();
        Response resp = client.newCall(req).execute();
        if (!resp.isSuccessful())
            throw new IOException("download failed: HTTP " + resp.code());

        ResponseBody body = resp.body();
        if (body == null) throw new IOException("empty response body");

        long total = body.contentLength();
        ByteArrayOutputStream out = new ByteArrayOutputStream(total > 0 ? (int) total : 8192);
        byte[] buf = new byte[8192];
        try (InputStream in = body.byteStream()) {
            int n;
            long done = 0;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
        }
        resp.close();
        return out.toByteArray();
    }

    private String resolveDownloadUrl(String publicUrl) throws Exception {
        String encoded = Uri.encode(publicUrl);
        Request req = new Request.Builder()
                .url("https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key=" + encoded)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful())
                throw new IOException("resolve download url failed: HTTP " + resp.code());
            return new JSONObject(resp.body().string()).getString("href");
        }
    }

    @Override
    public void delete(Context ctx, String publicUrl) throws Exception {
        Log.w(TAG, "delete not implemented for Yandex Disk");
    }

    @Override
    public View getSetupView(LayoutInflater inflater, ViewGroup parent, Context ctx) {
        View v = inflater.inflate(R.layout.view_provider_yandex, parent, false);

        MaterialButton auth = v.findViewById(R.id.button_yandex_auth);
        View debugGroup = v.findViewById(R.id.group_yandex_debug);
        TextInputEditText editToken = v.findViewById(R.id.edit_yandex_token);
        MaterialButton verify = v.findViewById(R.id.button_yandex_verify);
        MaterialButton disconnect = v.findViewById(R.id.button_yandex_disconnect);
        MaterialButton clearSession = v.findViewById(R.id.button_yandex_clear_session);

        initBodyState(v, ctx);

        if (BuildConfig.DEBUG) {
            debugGroup.setVisibility(View.VISIBLE);
        }

        auth.setOnClickListener(x -> {
            String clientId = ctx.getString(R.string.yandex_client_id);
            if ("YOUR_YANDEX_CLIENT_ID".equals(clientId)) {
                return;
            }
            Intent intent = new Intent(ctx, OAuthWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        });

        verify.setOnClickListener(x -> {
            CharSequence cs = editToken.getText();
            if (cs == null || cs.toString().trim().isEmpty()) return;
            String t = cs.toString().trim();
            new Thread(() -> {
                boolean ok;
                try {
                    Request req = new Request.Builder()
                            .url(API_BASE)
                            .header("Authorization", "OAuth " + t)
                            .build();
                    try (Response resp = client.newCall(req).execute()) {
                        ok = resp.isSuccessful();
                    }
                } catch (Exception e) {
                    ok = false;
                }
                final boolean fOk = ok;
                editToken.post(() -> {
                    if (fOk) {
                        prefs(ctx).edit().putString("access_token", t).apply();
                        initBodyState(v, ctx);
                    }
                });
            }).start();
        });

        disconnect.setOnClickListener(x -> {
            prefs(ctx).edit().remove("access_token").apply();
            initBodyState(v, ctx);
            editToken.setText("");
        });

        clearSession.setOnClickListener(x -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().removeAllCookies(null);
            } else {
                CookieManager.getInstance().removeAllCookie();
            }
        });

        return v;
    }

    private void fetchDiskInfo(String token, Context ctx,
                               TextView userText, TextView spaceText,
                               LinearProgressIndicator progress) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(API_BASE)
                        .header("Authorization", "OAuth " + token)
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful()) return;
                    String json = resp.body().string();
                    JSONObject obj = new JSONObject(json);

                    JSONObject userObj = obj.optJSONObject("user");
                    String userName = userObj != null
                            ? userObj.optString("display_name", null) : null;
                    long totalSpace = obj.optLong("total_space", 0);
                    long usedSpace = obj.optLong("used_space", 0);

                    userText.post(() -> {
                        if (userName != null && !userName.isEmpty()) {
                            userText.setText(ctx.getString(R.string.yandex_user_fmt, userName));
                            userText.setVisibility(View.VISIBLE);
                        }
                        if (totalSpace > 0) {
                            String used = formatBytes(usedSpace);
                            String total = formatBytes(totalSpace);
                            spaceText.setText(ctx.getString(R.string.yandex_space_fmt,
                                    used, total));
                            spaceText.setVisibility(View.VISIBLE);

                            int pct = (int) (usedSpace * 100 / totalSpace);
                            progress.setProgress(pct);
                            progress.setVisibility(View.VISIBLE);
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        if (exp > 5) exp = 5;
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, exp), pre);
    }

    private static final class CountingInputStream extends InputStream {
        private static final long THROTTLE = 64 * 1024;

        private final InputStream delegate;
        private final long total;
        private final ProgressListener listener;
        private long done;
        private long sinceEmit;

        CountingInputStream(InputStream delegate, long total, ProgressListener listener) {
            this.delegate = delegate;
            this.total = total;
            this.listener = listener;
        }

        @Override public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) bump(1);
            return b;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) bump(n);
            return n;
        }

        @Override public void close() throws IOException {
            delegate.close();
        }

        private void bump(long n) {
            done += n;
            sinceEmit += n;
            if (sinceEmit >= THROTTLE || done >= total) {
                sinceEmit = 0;
                if (listener != null) listener.onProgress(done, total);
            }
        }
    }
}
