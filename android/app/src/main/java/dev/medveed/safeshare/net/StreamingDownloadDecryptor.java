package dev.medveed.safeshare.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

import dev.medveed.safeshare.crypto.StreamingAesGcm;

public final class StreamingDownloadDecryptor {

    private StreamingDownloadDecryptor() { /* no instances */ }

    public static String decrypt(
            byte[] key16,
            byte[] r4,
            long cipherTotal,
            InputStream cipherIn,
            OutputStream plainOut,
            ProgressListener progress
    ) throws IOException, GeneralSecurityException {
        InputStream tracked = progress != null && cipherTotal > 0
                ? new CountingInputStream(cipherIn, cipherTotal, progress)
                : cipherIn;
        return StreamingAesGcm.decrypt(key16, r4, tracked, plainOut);
    }

    private static final class CountingInputStream extends InputStream {
        private static final long THROTTLE_BYTES = 64 * 1024;

        private final InputStream delegate;
        private final long total;
        private final ProgressListener listener;
        private long done = 0;
        private long sinceEmit = 0;

        CountingInputStream(InputStream delegate, long total, ProgressListener listener) {
            this.delegate = delegate;
            this.total = total;
            this.listener = listener;
        }

        @Override public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) bump(1);
            return b;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) bump(n);
            return n;
        }

        @Override public int available() throws IOException {
            return delegate.available();
        }

        @Override public void close() throws IOException {
            delegate.close();
        }

        private void bump(long n) {
            done += n;
            sinceEmit += n;
            if (sinceEmit >= THROTTLE_BYTES || done >= total) {
                sinceEmit = 0;
                listener.onProgress(done, total);
            }
        }
    }
}
