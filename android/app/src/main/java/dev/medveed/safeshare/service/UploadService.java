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
import java.security.KeyPair;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.medveed.safeshare.MainActivity;
import dev.medveed.safeshare.R;
import dev.medveed.safeshare.crypto.EcdhHelper;
import dev.medveed.safeshare.crypto.KeyMaterial;
import dev.medveed.safeshare.crypto.TransferCodeV2;
import dev.medveed.safeshare.db.AppDatabase;
import dev.medveed.safeshare.db.TransferDao;
import dev.medveed.safeshare.db.TransferEntity;
import dev.medveed.safeshare.util.NetworkUtil;
import dev.medveed.safeshare.net.ApiClient;
import dev.medveed.safeshare.net.ApiService;
import dev.medveed.safeshare.net.StreamingUploadBody;
import dev.medveed.safeshare.net.UploadResponse;
import dev.medveed.safeshare.net.storage.StorageProvider;
import dev.medveed.safeshare.net.storage.StorageProviders;
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
    public static final String EXTRA_RECIPIENT_PUB = "recipient_pub";
    public static final String EXTRA_STORAGE_PREFIX = "storage_prefix";

    private ExecutorService executor;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManager notificationManager;
    private final AtomicInteger lastStartId = new AtomicInteger(0);

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        notificationManager = getSystemService(NotificationManager.class);
        createChannelIfNeeded();
    }

    public static void start(
            Context context, Uri uri, String filename, long size,
            long ttlSeconds, long maxDownloads, byte[] recipientPubBytes,
            String storagePrefix
    ) {
        Intent i = new Intent(context, UploadService.class);
        i.putExtra(EXTRA_URI, uri);
        i.putExtra(EXTRA_FILENAME, filename);
        i.putExtra(EXTRA_SIZE, size);
        i.putExtra(EXTRA_TTL_SECONDS, ttlSeconds);
        i.putExtra(EXTRA_MAX_DOWNLOADS, maxDownloads);
        i.putExtra(EXTRA_RECIPIENT_PUB, recipientPubBytes);
        i.putExtra(EXTRA_STORAGE_PREFIX, storagePrefix);
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
        byte[] recipientPub = intent.getByteArrayExtra(EXTRA_RECIPIENT_PUB);
        String storagePrefix = intent.getStringExtra(EXTRA_STORAGE_PREFIX);
        if (uri == null || filename == null || size <= 0 || recipientPub == null) {
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
        final byte[] fRecipientPub = recipientPub;

        final String fStoragePrefix = storagePrefix;
        lastStartId.set(startId);

        executor.execute(() -> runUpload(fUri, fFilename, fSize, fTtl, fMax, fRecipientPub, fStoragePrefix));
        return START_NOT_STICKY;
    }

    private void runUpload(
            Uri uri, String filename, long size,
            long ttlSeconds, long maxDownloads,
            byte[] recipientPubBytes, String storagePrefix
    ) {
        TransferDao dao = AppDatabase.get(this).transferDao();
        StorageProvider provider = storagePrefix != null
                ? StorageProviders.byPrefix(storagePrefix)
                : StorageProviders.getDefault(this);
        if (provider == null) provider = StorageProviders.getDefault(this);

        TransferEntity row = new TransferEntity();
        row.direction = TransferEntity.DIRECTION_SEND;
        row.storagePrefix = provider.prefix();
        row.filename = filename;
        row.sizeBytes = size;
        row.createdAt = System.currentTimeMillis();
        row.status = TransferEntity.STATUS_IN_PROGRESS;
        long rowId = dao.insert(row);

        byte[] ownerToken = new byte[32];
        new SecureRandom().nextBytes(ownerToken);
        String ownerTokenHex = bytesToHex(ownerToken);
        String ownerTokenHashHex = bytesToHex(sha256(ownerToken));

        UploadController.get().post(new UploadController.State(
                UploadController.Stage.UPLOADING, 0, size,
                null, null, null, 0, null, null, rowId));

        PublicKey recipientPub;
        KeyPair ephemeralPair;
        KeyMaterial km;
        byte[] ephPubBytes;
        try {
            recipientPub = EcdhHelper.getPublicKeyFromRaw(recipientPubBytes);
            ephemeralPair = EcdhHelper.generateEphemeralKeyPair();
            byte[] sharedSecret = EcdhHelper.computeSharedSecret(ephemeralPair.getPrivate(), recipientPub);
            km = KeyMaterial.fromHkdf(EcdhHelper.hkdf(sharedSecret));
            ephPubBytes = EcdhHelper.getRawPublicKey(ephemeralPair.getPublic());
        } catch (Exception e) {
            Log.w(TAG, "crypto setup failed", e);
            String msg = dev.medveed.safeshare.util.ErrorMessages.describe(this, e);
            dao.setStatus(rowId, TransferEntity.STATUS_FAILED, msg);
            UploadController.get().post(new UploadController.State(
                    UploadController.Stage.FAILED, 0, size,
                    null, null, null, 0, null, msg, rowId));
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf(lastStartId.get());
            return;
        }

        try {
            NetworkUtil.requireNetwork(this);
        } catch (IOException e) {
            String msg = e.getMessage();
            dao.setStatus(rowId, TransferEntity.STATUS_FAILED, msg);
            UploadController.get().post(new UploadController.State(
                    UploadController.Stage.FAILED, 0, size,
                    null, null, null, 0, null, msg, rowId));
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf(lastStartId.get());
            return;
        }

        boolean isSafeShare = "s".equals(provider.prefix());

        if (isSafeShare) {
            ApiService api = ApiClient.get(this).service();
            runSafeShareUpload(api, dao, km, filename, size, uri, rowId, ownerTokenHex, ephPubBytes, ttlSeconds, maxDownloads, ownerTokenHashHex);
        } else {
            runProviderUpload(provider, dao, km, filename, size, uri, rowId, ownerTokenHex, ephPubBytes);
        }
    }

    private void runSafeShareUpload(
            ApiService api, TransferDao dao, KeyMaterial km, String filename, long size, Uri uri,
            long rowId, String ownerTokenHex, byte[] ephPubBytes,
            long ttlSeconds, long maxDownloads, String ownerTokenHashHex
    ) {
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

        try {
            String filenameB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Call<UploadResponse> call = api.upload(ttlSeconds, maxDownloads, ownerTokenHashHex, filenameB64, body);
            Response<UploadResponse> resp = call.execute();
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException(dev.medveed.safeshare.util.ErrorMessages.httpError(
                        resp.code(), resp.errorBody(), null));
            }
            UploadResponse ur = resp.body();
            String fileId = ur.file_id;
            String tcData = ApiClient.get(this).baseUrl() + "|" + fileId;
            TransferCodeV2 tc = new TransferCodeV2("s", tcData, ephPubBytes, filename);
            String transferCode = tc.format();
            String compactUri = tc.formatUri();
            long expiresAt = ur.expires_at * 1000L;

            dao.markSendDone(rowId, fileId, expiresAt, ownerTokenHex,
                    TransferEntity.STATUS_DONE, "s");

            UploadController.get().post(new UploadController.State(
                    UploadController.Stage.DONE, size, size,
                    fileId, transferCode, compactUri,
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
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf(lastStartId.get());
        }
    }

    private void runProviderUpload(
            StorageProvider provider, TransferDao dao, KeyMaterial km, String filename, long size, Uri uri,
            long rowId, String ownerTokenHex, byte[] ephPubBytes
    ) {
        try (InputStream plainIn = openInputOrThrow(uri)) {
            String publicUrl = provider.upload(this, plainIn, filename, size, km, (done, total) -> {
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                updateNotification(filename, done, total, pct);
                UploadController.get().post(new UploadController.State(
                        UploadController.Stage.UPLOADING, done, total,
                        null, null, null, 0, null, null, rowId));
            });

            TransferCodeV2 tc = new TransferCodeV2(provider.prefix(), publicUrl, ephPubBytes, filename);
            String transferCode = tc.format();
            long expiresAt = System.currentTimeMillis() + 7L * 24 * 3600 * 1000;

            dao.markSendDone(rowId, publicUrl, expiresAt, ownerTokenHex,
                    TransferEntity.STATUS_DONE, provider.prefix());

            UploadController.get().post(new UploadController.State(
                    UploadController.Stage.DONE, size, size,
                    publicUrl, transferCode, tc.formatUri(),
                    expiresAt, ownerTokenHex, null, rowId));

            notificationManager.notify(NOTIFICATION_ID,
                    makeDoneNotification(filename).build());
        } catch (Exception e) {
            Log.w(TAG, "provider upload failed", e);
            String msg = dev.medveed.safeshare.util.ErrorMessages.describe(this, e);
            dao.setStatus(rowId, TransferEntity.STATUS_FAILED, msg);
            UploadController.get().post(new UploadController.State(
                    UploadController.Stage.FAILED, 0, size,
                    null, null, null, 0, null, msg, rowId));
            notificationManager.notify(NOTIFICATION_ID,
                    makeFailedNotification(filename, msg).build());
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf(lastStartId.get());
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
        return dev.medveed.safeshare.util.FormatUtil.humanSize(bytes);
    }
}
