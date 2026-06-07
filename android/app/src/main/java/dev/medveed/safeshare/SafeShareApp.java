package dev.medveed.safeshare;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

import java.security.KeyPair;

import dev.medveed.safeshare.crypto.EcdhHelper;

public class SafeShareApp extends Application {

    private static final String TAG = "SafeShareApp";
    private static final String PREFS_IDENTITY = "identity";
    private static final String KEY_PUB_KEY = "pub_key";

    @Override
    public void onCreate() {
        super.onCreate();

        applySavedTheme();

        DynamicColors.applyToActivitiesIfAvailable(this);

        initIdentity();
    }

    private void applySavedTheme() {
        int mode = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void initIdentity() {
        SharedPreferences prefs = getSharedPreferences(PREFS_IDENTITY, MODE_PRIVATE);
        if (!prefs.contains(KEY_PUB_KEY)) {
            try {
                KeyPair kp = EcdhHelper.generateIdentityKeyPair("identity");
                byte[] pubBytes = EcdhHelper.getRawPublicKey(kp.getPublic());
                String pubB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
                prefs.edit().putString(KEY_PUB_KEY, pubB64).apply();
                Log.i(TAG, "Generated new identity keypair");
            } catch (Exception e) {
                Log.e(TAG, "Failed to generate identity keypair", e);
            }
        }
    }
}
