package dev.medveed.safeshare.net.storage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StorageProviders {

    private static final Map<String, StorageProvider> providers = new LinkedHashMap<>();

    static {
        register(new SafeShareProvider());
        register(new YandexDiskProvider());
    }

    private StorageProviders() {}

    public static void register(StorageProvider p) {
        providers.put(p.prefix(), p);
    }

    public static StorageProvider byPrefix(String prefix) {
        return providers.get(prefix);
    }

    public static StorageProvider getDefault(Context ctx) {
        String id = prefs(ctx).getString("default_storage", "s");
        StorageProvider p = providers.get(id);
        return p != null ? p : providers.get("s");
    }

    public static void setDefault(Context ctx, StorageProvider p) {
        prefs(ctx).edit().putString("default_storage", p.prefix()).apply();
    }

    public static Collection<StorageProvider> all() {
        return providers.values();
    }

    public static Collection<StorageProvider> available(Context ctx) {
        List<StorageProvider> result = new java.util.ArrayList<>();
        for (StorageProvider p : providers.values()) {
            if (p.isConfigured(ctx)) result.add(p);
        }
        return result;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("storage", 0);
    }
}
