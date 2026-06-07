package dev.medveed.safeshare.net.storage;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.io.InputStream;

import dev.medveed.safeshare.crypto.KeyMaterial;

public interface StorageProvider {
    String prefix();
    String displayName();
    String configId();

    boolean isConfigured(Context ctx);
    void disconnect(Context ctx);

    View getSetupView(LayoutInflater inflater, ViewGroup parent, Context ctx);

    void refreshView(View setupView, Context ctx);

    @Nullable
    String upload(Context ctx, InputStream plaintextIn, String name,
                  long plaintextSize, KeyMaterial km,
                  @Nullable ProgressListener progress) throws Exception;

    long contentLength(Context ctx, String publicUrl) throws Exception;

    byte[] download(Context ctx, String publicUrl,
                    @Nullable ProgressListener progress) throws Exception;

    void delete(Context ctx, String publicUrl) throws Exception;

    interface ProgressListener {
        void onProgress(long done, long total);
    }
}
