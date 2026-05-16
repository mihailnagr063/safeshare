package dev.medveed.safeshare.net;

public interface ProgressListener {
    void onProgress(long done, long total);
}
