package dev.medveed.safeshare;

import android.app.Application;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

import java.io.IOException;

import dev.medveed.safeshare.crypto.Bip39Assets;

public class SafeShareApp extends Application {

    private static final String TAG = "SafeShareApp";

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);

        // Load the BIP-39 English wordlist from assets
        try {
            Bip39Assets.init(this);
        } catch (IOException e) {
            Log.w(TAG, "failed to load bip39_en.txt: " + e.getMessage());
        }
    }
}
