package dev.medveed.safeshare;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import dev.medveed.safeshare.ui.onboarding.OnboardingActivity;
import dev.medveed.safeshare.util.FeatureDiscovery;


public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_PREFILL_CODE = "prefill_code";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!OnboardingActivity.isOnboardingDone(getSharedPreferences("onboarding", MODE_PRIVATE))) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        setupSystemBars();

        NavHostFragment host = (NavHostFragment)
                getSupportFragmentManager().findFragmentById(R.id.nav_host);
        if (host == null) return;
        NavController nav = host.getNavController();

        BottomNavigationView bottom = findViewById(R.id.bottom_nav);
        NavigationUI.setupWithNavController(bottom, nav);

        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            boolean hasBack = getSupportFragmentManager().getBackStackEntryCount() > 0;
            bottom.animate().cancel();
            if (hasBack) {
                bottom.setTranslationY(0);
                bottom.animate()
                        .translationY(bottom.getHeight())
                        .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                        .setDuration(350)
                        .withEndAction(() -> bottom.setVisibility(View.GONE))
                        .start();
            } else {
                bottom.setVisibility(View.VISIBLE);
                bottom.setTranslationY(bottom.getHeight());
                bottom.animate()
                        .translationY(0)
                        .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                        .setDuration(350)
                        .start();
            }
        });

        handleDeepLink(getIntent(), nav);

        maybeShowFeatureDiscovery();
    }

    private void maybeShowFeatureDiscovery() {
        SharedPreferences prefs = getSharedPreferences("discovery", 0);
        if (prefs.getBoolean("done", false)) return;

        findViewById(android.R.id.content).postDelayed(() -> {
            FeatureDiscovery.Step[] sendSteps = {
                    new FeatureDiscovery.Step(R.id.button_scan_recipient,
                            R.string.discovery_send_scan_title,
                            R.string.discovery_send_scan_desc),
                    new FeatureDiscovery.Step(R.id.button_pick,
                            R.string.discovery_send_pick_title,
                            R.string.discovery_send_pick_desc),
                    new FeatureDiscovery.Step(R.id.button_start,
                            R.string.discovery_send_upload_title,
                            R.string.discovery_send_upload_desc),
            };

            BottomNavigationView bottom = findViewById(R.id.bottom_nav);

            FeatureDiscovery.start(this, sendSteps, () -> {
                bottom.setSelectedItemId(R.id.nav_contacts);

                findViewById(android.R.id.content).postDelayed(() -> {
                    FeatureDiscovery.Step[] contactsSteps = {
                            new FeatureDiscovery.Step(R.id.button_add_contact,
                                    R.string.discovery_contacts_add_title,
                                    R.string.discovery_contacts_add_desc),
                            new FeatureDiscovery.Step(R.id.button_my_qr,
                                    R.string.discovery_contacts_qr_title,
                                    R.string.discovery_contacts_qr_desc),
                    };

                    FeatureDiscovery.start(MainActivity.this, contactsSteps, () -> {
                        bottom.setSelectedItemId(R.id.nav_send);
                        prefs.edit().putBoolean("done", true).apply();
                    });
                }, 800);
            });
        }, 600);
    }

    private void setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        boolean isDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(
                getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDark);
            controller.setAppearanceLightNavigationBars(!isDark);
        }

        View fragmentContainer = findViewById(R.id.nav_host);
        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(0, top, 0, 0);
            return insets;
        });

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        NavHostFragment host = (NavHostFragment)
                getSupportFragmentManager().findFragmentById(R.id.nav_host);
        if (host != null) handleDeepLink(intent, host.getNavController());
    }

    private void handleDeepLink(Intent intent, NavController nav) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        String scheme = data.getScheme();
        if (!"sshare".equals(scheme)) return;

        String ssp = data.getSchemeSpecificPart();
        if (ssp == null) return;
        if (ssp.startsWith("//")) ssp = ssp.substring(2);

        Bundle args = new Bundle();
        args.putString(EXTRA_PREFILL_CODE, ssp);
        BottomNavigationView bottom = findViewById(R.id.bottom_nav);
        bottom.setSelectedItemId(R.id.nav_receive);
        nav.navigate(R.id.nav_receive, args);
    }
}
