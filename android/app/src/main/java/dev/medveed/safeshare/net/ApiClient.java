package dev.medveed.safeshare.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.TimeUnit;

import dev.medveed.safeshare.BuildConfig;
import dev.medveed.safeshare.R;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class ApiClient {

    private static final String PREFS = "net";
    private static final String KEY_BASE_URL = "base_url";

    private static volatile ApiClient instance;

    private final ApiService service;
    private final String baseUrl;

    private ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.HEADERS
                : HttpLoggingInterceptor.Level.NONE);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.MINUTES)
                .callTimeout(0, TimeUnit.MILLISECONDS) // unbounded
                .retryOnConnectionFailure(true)
                .addInterceptor(logging)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(this.baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        this.service = retrofit.create(ApiService.class);
    }

    public static ApiClient get(Context context) {
        ApiClient local = instance;
        if (local != null) return local;
        synchronized (ApiClient.class) {
            if (instance == null) {
                instance = new ApiClient(resolveBaseUrl(context));
            }
            return instance;
        }
    }

    public static synchronized void invalidate() {
        instance = null;
    }

    public ApiService service() {
        return service;
    }

    public String baseUrl() {
        return baseUrl;
    }

    private static String resolveBaseUrl(Context context) {
        SharedPreferences sp =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String user = sp.getString(KEY_BASE_URL, null);
        if (user != null && !user.isEmpty()) return user;
        String res = context.getString(R.string.safeshare_api_base);
        if (!res.isEmpty()) return res;
        return BuildConfig.API_BASE_URL;
    }

    public static void setBaseUrl(Context context, String url) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BASE_URL, url)
                .apply();
        invalidate();
    }
}
