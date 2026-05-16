package dev.medveed.safeshare.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicLong;

import dev.medveed.safeshare.crypto.KeyMaterial;
import dev.medveed.safeshare.crypto.StreamingAesGcm;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

public final class StreamingUploadBody extends RequestBody {
    public interface Supplier {
        InputStream open() throws IOException;
    }

    private final KeyMaterial km;
    private final String filename;
    private final long plaintextSize;
    private final Supplier supplier;
    private final ProgressListener progress;

    // Progress is reported in plaintext bytes. We wire a wrapping
    // InputStream that increments this atomic counter on every read.
    private final AtomicLong plaintextDone = new AtomicLong(0);

    public StreamingUploadBody(
            KeyMaterial km,
            String filename,
            long plaintextSize,
            Supplier supplier,
            ProgressListener progress
    ) {
        this.km = km;
        this.filename = filename;
        this.plaintextSize = plaintextSize;
        this.supplier = supplier;
        this.progress = progress;
    }

    @Override
    public MediaType contentType() {
        return MediaType.get("application/octet-stream");
    }

    @Override
    public long contentLength() {
        // Ciphertext size
        long nameCtLen = utf8Len(filename) + StreamingAesGcm.TAG_LEN;
        long chunks = (plaintextSize + StreamingAesGcm.CHUNK_SIZE - 1)
                / StreamingAesGcm.CHUNK_SIZE;
        long chunkOverhead = chunks * StreamingAesGcm.TAG_LEN;
        return StreamingAesGcm.HEADER_LEN
                + 2L
                + nameCtLen
                + plaintextSize
                + chunkOverhead;
    }

    @Override
    public boolean isOneShot() {
        return false;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        plaintextDone.set(0);
        try (InputStream in = supplier.open()) {
            InputStream tracked = new ProgressInputStream(in, plaintextDone, plaintextSize, progress);
            OutputStream out = sink.outputStream();
            StreamingAesGcm.encrypt(km, filename, plaintextSize, tracked, out);
        } catch (GeneralSecurityException e) {
            throw new IOException("encryption failed", e);
        }
    }

    private static int utf8Len(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static final class ProgressInputStream extends InputStream {
        private static final long THROTTLE_BYTES = 64 * 1024;

        private final InputStream delegate;
        private final AtomicLong done;
        private final long total;
        private final ProgressListener listener;
        private long bytesSinceEmit = 0;

        ProgressInputStream(InputStream delegate, AtomicLong done, long total,
                            ProgressListener listener) {
            this.delegate = delegate;
            this.done = done;
            this.total = total;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) bump(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) bump(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = delegate.skip(n);
            if (s > 0) bump(s);
            return s;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void bump(long n) {
            long now = done.addAndGet(n);
            bytesSinceEmit += n;
            if (bytesSinceEmit >= THROTTLE_BYTES || now >= total) {
                bytesSinceEmit = 0;
                if (listener != null) listener.onProgress(now, total);
            }
        }
    }
}
