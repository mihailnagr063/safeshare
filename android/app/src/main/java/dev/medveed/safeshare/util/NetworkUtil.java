package dev.medveed.safeshare.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;

import dev.medveed.safeshare.R;

public final class NetworkUtil {

    private NetworkUtil() {}

    public static boolean isOnline(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public static void requireNetwork(Context ctx) throws IOException {
        if (!isOnline(ctx)) {
            throw new IOException(ctx.getString(R.string.err_no_network));
        }
    }
}
