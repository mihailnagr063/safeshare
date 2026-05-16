package dev.medveed.safeshare.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.medveed.safeshare.MainActivity;
import dev.medveed.safeshare.R;
import dev.medveed.safeshare.crypto.CrockfordBase32;
import dev.medveed.safeshare.crypto.KeyMaterial;
import dev.medveed.safeshare.crypto.TransferCode;
import dev.medveed.safeshare.db.AppDatabase;
import dev.medveed.safeshare.db.TransferDao;
import dev.medveed.safeshare.db.TransferEntity;
import dev.medveed.safeshare.net.ApiClient;
import dev.medveed.safeshare.net.ApiService;
import dev.medveed.safeshare.net.StreamingUploadBody;
import dev.medveed.safeshare.net.UploadResponse;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class UploadService extends Service {

    private static final String TAG = "UploadService";
    private static final String CHANNEL_ID = "safeshare_upload";
    private static final int NOTIFICATION_ID = 101;

    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_FILENAME = "filename";
    public static final String EXTRA_SIZE = "size";
    public static final String EXTRA_TTL_SECONDS = "ttl_seconds";
    public static final String EXTRA_MAX_DOWNLOADS = "max_downloads";

    private ExecutorService executor;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        notificationManager = getSystemService(NotificationManager.class);
        createChannelIfNeeded();
    }

    public static void start(
            Context context, Uri uri, String filename, long size,
            long ttlSeconds, long maxDownloads
    ) {
        Intent i = new Intent(context, UploadService.class);
        i.putExtra(EXTRA_URI, uri);
        i.putExtra(EXTRA_FILENAME, filename);
        i.putExtra(EXTRA_SIZE, size);
        i.putExtra(EXTRA_TTL_SECONDS, ttlSeconds);
        i.putExtra(EXTRA_MAX_DOWNLOADS, maxDownloads);
        context.startForegroundService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        Uri uri = intent.getParcelableExtra(EXTRA_URI);
        String filename = intent.getStringExtra(EXTRA_FILENAME);
        long size = intent.getLongExtra(EXTRA_SIZE, -1);
        long ttlSeconds = intent.getLongExtra(EXTRA_TTL_SECONDS, 24 * 3600);
        long maxDownloads = intent.getLongExtra(EXTRA_MAX_DOWNLOADS, 3);
        if (uri == null || filename == null || size <= 0) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        notificationBuilder = makeNotification(filename, 0, size);
        startForeground(NOTIFICATION_ID, notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        final Uri fUri = uri;
        final String fFilename = filename;
        final long fSize = size;
        final long fTtl = ttlSeconds;
        final long fMax = maxDownloads;

        executor.execute(() -> runUpload(fUri, fFilename, fSize, fTtl, fMax));
        return START_NOT_STICKY;
    }

    private void runUpload(
            Uri uri, String filename, long size,
            long ttlSeconds, long maxDownloads
    ) {
        TransferDao dao = AppDatabase.get(this).transferDao();
        TransferEntity row = new TransferEntity();
        row.direction = TransferEntity.DIRECTION_SEND;
        row.filename = filename;
        row.sizeBytes = size;
        row.createdAt = System.currentTimeMillis();
        row.status = TransferEntity.STATUS_IN_PROGRESS;
        long rowId = dao.insert(row);

        KeyMaterial km = KeyMaterial.generate();
        byte[] fileIdBytes = new byte[5];
        new SecureRandom().nextBytes(fileIdBytes);
        String expectedFileId = CrockfordBase32.encode5(fileIdBytes);

        byte[] ownerToken = new byte[32];
        new SecureRandom().nextBytes(ownerToken);
        String ownerTokenHex = bytesToHex(ownerToken);
        String ownerTokenHashHex = bytesToHex(sha256(ownerToken));

        UploadController.get().post(new UploadController.State(
                UploadController.Stage.UPLOADING, 0, size,
                null, null, null, 0, null, null, rowId));

        StreamingUploadBody body = new StreamingUploadBody(
                km, filename, size,
                () -> openInputOrThrow(uri),
                (done, total) -> {
                    UploadController.get().post(new UploadController.State(
                            UploadController.Stage.UPLOADING, done, total,
                            null, null, null, 0, null, null, rowId));
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    updateNotification(filename, done, total, pct);
                });

        ApiService api = ApiClient.get(this).service();
        try {
            Call<UploadResponse> call = api.upload(ttlSeconds, maxDownloads, ownerTokenHashHex, body);
            Response<UploadResponse> resp = call.execute();
            if (!resp.isSuccessful() || resp.body() == null) {
                String msg = "HTTP " + resp.code();
                try (ResponseBody err = resp.errorBody()) {
                    if (err != null) msg += ": " + err.string();
                } catch (IOException ignored) { /* ok */ }
                throw new IOException(msg);
            }
            UploadResponse ur = resp.body();
            String fileId = ur.file_id;
            long expiresAt = ur.expires_at * 1000L;

            byte[] idBytes = CrockfordBase32.decode5(fileId);
            TransferCode tc = new TransferCode(idBytes, km);

            String compactUri = tc.formatSshareUri();

            String verboseCode;
            try {
                verboseCode = tc.format();
            } catch (Exception e) {
                Log.w(TAG, "cannot format BIP-39 code: " + e.getMessage());
                verboseCode = tc.formatCompact();
            }

            dao.markSendDone(rowId, fileId, expiresAt, ownerTokenHex,
                    TransferEntity.STATUS_DONE);

            UploadController.get().post(new UploadController.State(
                    UploadController.Stage.DONE, size, size,
                    fileId, verboseCode, compactUri,
                    expiresAt, ownerTokenHex, null, rowId));

            notificationManager.notify(NOTIFICATION_ID,
                    makeDoneNotification(filename).build());
        } catch (Exception e) {
            Log.w(TAG, "upload failed", e);
            String msg = dev.medveed.safeshare.util.ErrorMessages.describe(this, e);
            dao.setStatus(rowId, TransferEntity.STATUS_FAILED, msg);
            UploadController.get().post(new UploadController.State(
                    UploadController.Stage.FAILED, 0, size,
                    null, null, null, 0, null, msg, rowId));
            notificationManager.notify(NOTIFICATION_ID,
                    makeFailedNotification(filename, msg).build());
        } finally {
            if (expectedFileId.isEmpty()) { /* unused */ }
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private InputStream openInputOrThrow(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open " + uri);
        return in;
    }

    private void updateNotification(String filename, long done, long total, int pct) {
        notificationBuilder
                .setContentText(String.format(java.util.Locale.US,
                        "%d%% — %s", pct, humanSize(done) + "/" + humanSize(total)))
                .setProgress(100, pct, false);
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private NotificationCompat.Builder makeNotification(String filename, long done, long total) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.upload_notification_title, filename))
                .setContentText("0%")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setProgress(100, 0, false);
    }

    private NotificationCompat.Builder makeDoneNotification(String filename) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.upload_notification_done, filename))
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .setContentIntent(pi);
    }

    private NotificationCompat.Builder makeFailedNotification(String filename, String reason) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.upload_notification_failed, filename))
                .setContentText(reason)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(pi);
    }

    private void createChannelIfNeeded() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.upload_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.upload_channel_desc));
        notificationManager.createNotificationChannel(ch);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }

    // --- small helpers ------------------------------------------------

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = alphabet[(bytes[i] >> 4) & 0xf];
            out[i * 2 + 1] = alphabet[bytes[i] & 0xf];
        }
        return new String(out);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
