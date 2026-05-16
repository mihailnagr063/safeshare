package dev.medveed.safeshare;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;


public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_PREFILL_CODE = "prefill_code";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        NavHostFragment host = (NavHostFragment)
                getSupportFragmentManager().findFragmentById(R.id.nav_host);
        if (host == null) return;
        NavController nav = host.getNavController();

        BottomNavigationView bottom = findViewById(R.id.bottom_nav);
        NavigationUI.setupWithNavController(bottom, nav);

        handleDeepLink(getIntent(), nav);
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
        nav.navigate(R.id.nav_receive, args);
    }
}
