package dev.medveed.safeshare.ui.oauth;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import dev.medveed.safeshare.R;

public class OAuthWebViewActivity extends AppCompatActivity {

    static final String REDIRECT_URI = "https://localhost/";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oauth_webview);

        ProgressBar progress = findViewById(R.id.progress_oauth);
        WebView webView = findViewById(R.id.webview_oauth);

        String clientId = getString(R.string.yandex_client_id);
        String authUrl = "https://oauth.yandex.com/authorize"
                + "?response_type=token"
                + "&client_id=" + clientId
                + "&redirect_uri=" + REDIRECT_URI
                + "&scope=cloud_api:disk.app_folder";

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                if (url.startsWith(REDIRECT_URI)) {
                    parseAndSaveToken(url);
                    finish();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(REDIRECT_URI)) {
                    parseAndSaveToken(url);
                    finish();
                    return true;
                }
                return false;
            }
        });

        @SuppressLint("SetJavaScriptEnabled")
        android.webkit.WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        webView.loadUrl(authUrl);
    }

    private void parseAndSaveToken(String url) {
        int hash = url.indexOf('#');
        if (hash < 0) return;
        String fragment = url.substring(hash + 1);
        String[] params = fragment.split("&");
        String token = null;
        for (String p : params) {
            if (p.startsWith("access_token=")) {
                token = p.substring("access_token=".length());
                break;
            }
        }
        if (token != null && !token.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("yandex_disk", 0);
            prefs.edit().putString("access_token", token).apply();
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_CANCELED);
        }
    }
}
