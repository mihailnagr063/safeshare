package dev.medveed.safeshare.ui.settings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.net.storage.StorageProvider;
import dev.medveed.safeshare.net.storage.StorageProviders;

public class StorageSettingsFragment extends Fragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private LayoutInflater inflater;
    private ViewGroup container;
    private final String[] watchedPrefs = {"yandex_disk"};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        this.inflater = inflater;
        return inflater.inflate(R.layout.fragment_storage_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(x -> close());

        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        close();
                    }
                });

        container = v.findViewById(R.id.container_providers);

        setupDefaultRadio(v);
        buildCards();
    }

    @Override
    public void onResume() {
        super.onResume();
        for (String name : watchedPrefs) {
            requireContext().getSharedPreferences(name, 0)
                    .registerOnSharedPreferenceChangeListener(this);
        }
        View v = getView();
        if (v != null) {
            setupDefaultRadio(v);
            refreshCards();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        for (String name : watchedPrefs) {
            requireContext().getSharedPreferences(name, 0)
                    .unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if ("access_token".equals(key) && isVisible()) {
            View v = getView();
            if (v != null) {
                setupDefaultRadio(v);
                refreshCards();
            }
        }
    }

    private void setupDefaultRadio(View v) {
        RadioGroup groupDefault = v.findViewById(R.id.group_storage_default);

        String firstConfiguredPrefix = null;
        for (StorageProvider p : StorageProviders.all()) {
            int radioId = getRadioId(p.prefix());
            View radio = v.findViewById(radioId);
            if (radio != null) {
                boolean configured = p.isConfigured(requireContext());
                radio.setEnabled(configured);
                radio.setAlpha(configured ? 1f : 0.4f);
                if (configured && firstConfiguredPrefix == null) {
                    firstConfiguredPrefix = p.prefix();
                }
            }
        }

        StorageProvider defaultProvider = StorageProviders.getDefault(requireContext());
        if (!defaultProvider.isConfigured(requireContext()) && firstConfiguredPrefix != null) {
            defaultProvider = StorageProviders.byPrefix(firstConfiguredPrefix);
            StorageProviders.setDefault(requireContext(), defaultProvider);
        }
        groupDefault.check("y".equals(defaultProvider.prefix())
                ? R.id.storage_default_yandex : R.id.storage_default_safeshare);

        groupDefault.setOnCheckedChangeListener((g, id) -> {
            String prefix = id == R.id.storage_default_yandex ? "y" : "s";
            StorageProviders.setDefault(requireContext(), StorageProviders.byPrefix(prefix));
        });
    }

    private void refreshCards() {
        int i = 0;
        for (StorageProvider provider : StorageProviders.all()) {
            View cardView = container.getChildAt(i);
            if (cardView != null) {
                updateCardStatus(cardView, provider);
                ViewGroup body = cardView.findViewById(android.R.id.content);
                if (body != null && body.getChildCount() > 0) {
                    provider.refreshView(body.getChildAt(0), requireContext());
                }
            }
            i++;
        }
    }

    private void updateCardStatus(View cardView, StorageProvider provider) {
        ImageView icon = cardView.findViewById(R.id.icon_status);
        TextView text = cardView.findViewById(R.id.text_status);
        boolean connected = provider.isConfigured(requireContext());
        icon.setImageResource(connected ? R.drawable.ic_check_circle : R.drawable.ic_cancel_circle);
        icon.setColorFilter(connected ? 0xFF4CAF50 : 0xFF9E9E9E, PorterDuff.Mode.SRC_IN);
        text.setText(connected ? R.string.status_connected : R.string.status_disconnected);
    }

    private void buildCards() {
        container.removeAllViews();
        for (StorageProvider provider : StorageProviders.all()) {
            container.addView(buildCard(provider));
        }
    }

    private MaterialCardView buildCard(StorageProvider provider) {
        MaterialCardView card = (MaterialCardView) inflater.inflate(
                R.layout.card_provider_accordion, container, false);

        TextView title = card.findViewById(android.R.id.title);
        title.setText(provider.displayName());

        updateCardStatus(card, provider);

        ImageView chevron = card.findViewById(R.id.icon_chevron);

        ViewGroup body = card.findViewById(android.R.id.content);
        body.removeAllViews();
        View config = provider.getSetupView(inflater, body, requireContext());
        body.addView(config);
        body.getLayoutParams().height = 0;
        body.setVisibility(View.GONE);

        card.setClickable(true);
        card.setOnClickListener(v -> toggleCard(body, chevron));

        return card;
    }

    private void toggleCard(View body, View chevron) {
        boolean expanding = body.getVisibility() != View.VISIBLE;

        body.measure(
                View.MeasureSpec.makeMeasureSpec(
                        ((View) body.getParent()).getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        int targetHeight = expanding ? body.getMeasuredHeight() : 0;
        int startHeight = expanding ? 0 : body.getHeight();

        if (expanding) body.setVisibility(View.VISIBLE);

        ValueAnimator anim = ValueAnimator.ofInt(startHeight, targetHeight);
        anim.addUpdateListener(a -> {
            ViewGroup.LayoutParams p = body.getLayoutParams();
            p.height = (int) a.getAnimatedValue();
            body.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                body.getLayoutParams().height = expanding
                        ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
                if (!expanding) body.setVisibility(View.GONE);
            }
        });
        anim.setDuration(200);
        anim.start();

        chevron.animate()
                .rotation(expanding ? 90 : 0)
                .setDuration(200)
                .start();
    }

    private void close() {
        requireActivity().getSupportFragmentManager().popBackStack();
    }

    private static int getRadioId(String prefix) {
        return "y".equals(prefix) ? R.id.storage_default_yandex : R.id.storage_default_safeshare;
    }
}
