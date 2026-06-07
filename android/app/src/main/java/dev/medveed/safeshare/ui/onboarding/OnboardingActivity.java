package dev.medveed.safeshare.ui.onboarding;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;

import dev.medveed.safeshare.MainActivity;
import dev.medveed.safeshare.R;

public class OnboardingActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "onboarding";
    private static final String KEY_DONE = "done";

    private ViewPager2 pager;
    private LinearLayout dotsContainer;
    private MaterialButton buttonNext;
    private Button buttonSkip;
    private View[] dots;

    private final PageData[] pages = {
            new PageData(R.drawable.ic_shield, R.string.onboarding_welcome_title, R.string.onboarding_welcome_desc),
            new PageData(R.drawable.ic_qr_code, R.string.onboarding_send_title, R.string.onboarding_send_desc),
            new PageData(R.drawable.ic_file, R.string.onboarding_receive_title, R.string.onboarding_receive_desc),
    };

    private void setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        int flags = 0;
        boolean isDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (!isDark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0, 0, 0, bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isOnboardingDone()) {
            startMainActivity();
            return;
        }

        setContentView(R.layout.activity_onboarding);
        setupSystemBars();

        pager = findViewById(R.id.pager);
        dotsContainer = findViewById(R.id.dots_container);
        buttonNext = findViewById(R.id.button_next);
        buttonSkip = findViewById(R.id.button_skip);

        pager.setAdapter(new OnboardingAdapter(pages));
        pager.setOffscreenPageLimit(pages.length);
        pager.setUserInputEnabled(true);

        setupDots(pages.length);
        updateDots(0);
        updateButton(0);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(position);
                updateButton(position);
            }
        });

        buttonNext.setOnClickListener(v -> {
            int current = pager.getCurrentItem();
            if (current < pages.length - 1) {
                pager.setCurrentItem(current + 1, true);
            } else {
                finishOnboarding();
            }
        });

        buttonSkip.setOnClickListener(v -> finishOnboarding());
    }

    private void setupDots(int count) {
        dots = new View[count];
        int size = getResources().getDimensionPixelSize(R.dimen.dot_size);
        int margin = getResources().getDimensionPixelSize(R.dimen.dot_margin);
        for (int i = 0; i < count; i++) {
            dots[i] = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            dots[i].setLayoutParams(lp);
            dots[i].setBackgroundResource(R.drawable.shape_dot);
            dotsContainer.addView(dots[i]);
        }
    }

    private void updateDots(int position) {
        for (int i = 0; i < dots.length; i++) {
            dots[i].setAlpha(i == position ? 1.0f : 0.3f);
        }
    }

    private void updateButton(int position) {
        if (position == pages.length - 1) {
            buttonNext.setText(R.string.onboarding_get_started);
        } else {
            buttonNext.setText(R.string.onboarding_next);
        }
    }

    private void finishOnboarding() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DONE, true)
                .apply();
        startMainActivity();
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        Uri data = getIntent().getData();
        if (data != null) {
            intent.setData(data);
        }
        startActivity(intent);
        finish();
    }

    public static boolean isOnboardingDone(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_DONE, false);
    }

    private boolean isOnboardingDone() {
        return isOnboardingDone(getSharedPreferences(PREFS_NAME, MODE_PRIVATE));
    }
}
