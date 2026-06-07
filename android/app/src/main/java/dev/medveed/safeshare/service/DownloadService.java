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
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.medveed.safeshare.MainActivity;
import dev.medveed.safeshare.R;
import dev.medveed.safeshare.crypto.EcdhHelper;
import dev.medveed.safeshare.crypto.KeyMaterial;
import dev.medveed.safeshare.crypto.StreamingAesGcm;
import dev.medveed.safeshare.crypto.TransferCodeV2;
import dev.medveed.safeshare.db.AppDatabase;
import dev.medveed.safeshare.db.TransferDao;
import dev.medveed.safeshare.db.TransferEntity;
import dev.medveed.safeshare.net.ApiClient;
import dev.medveed.safeshare.net.ApiService;
import dev.medveed.safeshare.net.StreamingDownloadDecryptor;
import dev.medveed.safeshare.net.storage.StorageProvider;
import dev.medveed.safeshare.net.storage.StorageProviders;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final String CHANNEL_ID = "safeshare_download";
    private static final int NOTIFICATION_ID = 102;

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_OUTPUT_URI = "output_uri";
    public static final String EXTRA_SAVED_FILENAME = "saved_filename";

    private ExecutorService executor;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManager notificationManager;

    public static void start(Context context, String transferCode, Uri outputUri, @Nullable String savedFilename) {
        Intent i = new Intent(context, DownloadService.class);
        i.putExtra(EXTRA_CODE, transferCode);
        i.putExtra(EXTRA_OUTPUT_URI, outputUri);
        i.putExtra(EXTRA_SAVED_FILENAME, savedFilename);
        context.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        notificationManager = getSystemService(NotificationManager.class);
        createChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String code = intent.getStringExtra(EXTRA_CODE);
        Uri output = intent.getParcelableExtra(EXTRA_OUTPUT_URI);
        String savedFilename = intent.getStringExtra(EXTRA_SAVED_FILENAME);
        if (code == null || output == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        notificationBuilder = makeNotification(0);
        startForeground(NOTIFICATION_ID, notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        final String fCode = code;
        final Uri fOutput = output;
        final String fSavedFilename = savedFilename;
        executor.execute(() -> runDownload(fCode, fOutput, fSavedFilename));
        return START_NOT_STICKY;
    }

    private void runDownload(String codeStr, Uri output, @Nullable String savedFilename) {
        TransferDao dao = AppDatabase.get(this).transferDao();
        TransferEntity row = new TransferEntity();
        row.direction = TransferEntity.DIRECTION_RECEIVE;
        row.createdAt = System.currentTimeMillis();
        row.status = TransferEntity.STATUS_IN_PROGRESS;
        row.filename = "";
        long rowId = dao.insert(row);

        KeyMaterial km;
        TransferCodeV2 tc;
        try {
            tc = TransferCodeV2.parse(codeStr);

            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            PrivateKey identityPriv = (PrivateKey) ks.getKey("identity", null);

            PublicKey ephPub = EcdhHelper.getPublicKeyFromRaw(tc.ephPub);
            byte[] sharedSecret = EcdhHelper.computeSharedSecret(identityPriv, ephPub);
            km = KeyMaterial.fromHkdf(EcdhHelper.hkdf(sharedSecret));
        } catch (Exception e) {
            finishFailed(dao, rowId, getString(R.string.download_invalid_code, e.getMessage()));
            return;
        }

        DownloadController.get().post(new DownloadController.State(
                DownloadController.Stage.DOWNLOADING, 0, 0,
                null, null, null, null, rowId));

        if ("s".equals(tc.storagePrefix)) {
            String baseUrl = TransferCodeV2.extractSafeShareBaseUrl(tc.data);
            String fileId = TransferCodeV2.extractFileId("s", tc.data);
            if (fileId == null) {
                finishFailed(dao, rowId, "invalid transfer code: missing fileId");
                return;
            }
            ApiService api = baseUrl != null
                    ? ApiClient.createForBaseUrl(baseUrl).service()
                    : ApiClient.get(this).service();
            runSafeShareDownload(api, dao, rowId, km, fileId, tc, output, savedFilename);
        } else {
            StorageProvider provider = StorageProviders.byPrefix(tc.storagePrefix);
            if (provider == null) {
                finishFailed(dao, rowId, "Unknown storage provider: " + tc.storagePrefix);
                return;
            }
            runProviderDownload(provider, dao, rowId, km, tc, output, savedFilename);
        }
    }

    private void runSafeShareDownload(
            ApiService api, TransferDao dao, long rowId, KeyMaterial km,
            String fileId, TransferCodeV2 tc, Uri output, @Nullable String savedFilename
    ) {
        try {
            Call<ResponseBody> call = api.download(fileId);
            Response<ResponseBody> resp = call.execute();
            if (!resp.isSuccessful() || resp.body() == null) {
                String msg = "HTTP " + resp.code();
                try (ResponseBody err = resp.errorBody()) {
                    if (err != null) {
                        String body = err.string();
                        if (!body.isEmpty() && resp.code() >= 500) msg += " (" + body + ")";
                    }
                } catch (IOException ignored) { }
                throw new IOException(msg);
            }
            long cipherTotal = resp.body().contentLength();

            String originalFilename;
            {
                String filenameB64 = resp.headers().get("X-SafeShare-Filename");
                if (filenameB64 != null && !filenameB64.isEmpty()) {
                    try {
                        byte[] decoded = Base64.getUrlDecoder().decode(filenameB64);
                        originalFilename = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        originalFilename = null;
                    }
                } else {
                    originalFilename = null;
                }
            }

            String filename;
            if (savedFilename != null && !savedFilename.isEmpty()) {
                filename = savedFilename;
            } else if (originalFilename != null) {
                filename = originalFilename;
            } else {
                filename = "";
            }

            long plaintextSize = 0;
            try (ResponseBody body = resp.body();
                 InputStream rawIn = body.byteStream();
                 OutputStream out = getContentResolver().openOutputStream(output)) {
                if (out == null) throw new IOException("cannot open output URI");

                PushbackInputStream pushback = new PushbackInputStream(rawIn, 24);
                byte[] hdr = new byte[24];
                int read = 0;
                while (read < 24) {
                    int n = pushback.read(hdr, read, 24 - read);
                    if (n < 0) throw new IOException("truncated SSF1 header");
                    read += n;
                }
                pushback.unread(hdr);
                if ("SSF1".equals(new String(hdr, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))) {
                    plaintextSize = ByteBuffer.wrap(hdr, 12, 8).getLong();
                }

                StreamingDownloadDecryptor.decrypt(
                        km.key, km.r, cipherTotal,
                        pushback, out,
                        (done, total) -> {
                            DownloadController.get().post(new DownloadController.State(
                                    DownloadController.Stage.DOWNLOADING, done, total,
                                    null, null, null, null, rowId));
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            updateNotification(pct);
                        });
            }

            try {
                getContentResolver().takePersistableUriPermission(
                        output, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }

            TransferEntity existing = dao.byId(rowId);
            if (existing != null) {
                existing.fileId = fileId;
                existing.storagePrefix = "s";
                existing.filename = filename;
                if (plaintextSize > 0) existing.sizeBytes = plaintextSize;
                existing.savedUri = output.toString();
                existing.status = TransferEntity.STATUS_DONE;
                dao.update(existing);
            }

            DownloadController.get().post(new DownloadController.State(
                    DownloadController.Stage.DONE,
                    cipherTotal > 0 ? cipherTotal : 1,
                    cipherTotal > 0 ? cipherTotal : 1,
                    filename, originalFilename, output, null, rowId));

            notificationManager.notify(NOTIFICATION_ID,
                    makeDoneNotification(filename).build());
        } catch (Exception e) {
            Log.w(TAG, "safe share download failed", e);
            String msg = dev.medveed.safeshare.util.ErrorMessages.describe(this, e);
            try {
                android.database.Cursor c = getContentResolver().query(output, null, null, null, null);
                if (c != null) c.close();
                android.provider.DocumentsContract.deleteDocument(getContentResolver(), output);
            } catch (Exception ignored) { }
            finishFailed(dao, rowId, msg);
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private void runProviderDownload(
            StorageProvider provider, TransferDao dao, long rowId, KeyMaterial km,
            TransferCodeV2 tc, Uri output, @Nullable String savedFilename
    ) {
        try {
            String publicUrl = tc.data;

            updateNotification(0);

            long cipherLen = provider.contentLength(this, publicUrl);
            DownloadController.get().post(new DownloadController.State(
                    DownloadController.Stage.DOWNLOADING, 0, cipherLen,
                    null, null, null, null, rowId));

            byte[] ciphertext = provider.download(this, publicUrl, (done, total) -> {
                DownloadController.get().post(new DownloadController.State(
                        DownloadController.Stage.DOWNLOADING, done, total,
                        null, null, null, null, rowId));
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                updateNotification(pct);
            });

            long plaintextSize = 0;
            if (ciphertext.length >= 24) {
                if ("SSF1".equals(new String(ciphertext, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))) {
                    plaintextSize = ByteBuffer.wrap(ciphertext, 12, 8).getLong();
                }
            }

            StreamingAesGcm.DecryptResult decrypted = StreamingAesGcm.decryptFromBytes(km.key, km.r, ciphertext);
            String originalFilename = decrypted.filename;
            String displayFilename;
            if (savedFilename != null && !savedFilename.isEmpty()) {
                displayFilename = savedFilename;
            } else if (originalFilename != null) {
                displayFilename = originalFilename;
            } else {
                displayFilename = "";
            }

            try (OutputStream out = getContentResolver().openOutputStream(output)) {
                if (out == null) throw new IOException("cannot open output URI");
                out.write(decrypted.plaintext);
            }

            try {
                getContentResolver().takePersistableUriPermission(
                        output, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }

            TransferEntity existing = dao.byId(rowId);
            if (existing != null) {
                existing.fileId = tc.data;
                existing.storagePrefix = tc.storagePrefix;
                existing.filename = displayFilename;
                if (plaintextSize > 0) existing.sizeBytes = plaintextSize;
                existing.savedUri = output.toString();
                existing.status = TransferEntity.STATUS_DONE;
                dao.update(existing);
            }

            DownloadController.get().post(new DownloadController.State(
                    DownloadController.Stage.DONE,
                    ciphertext.length > 0 ? ciphertext.length : 1,
                    ciphertext.length > 0 ? ciphertext.length : 1,
                    displayFilename, originalFilename, output, null, rowId));

            notificationManager.notify(NOTIFICATION_ID,
                    makeDoneNotification(displayFilename).build());
        } catch (Exception e) {
            Log.w(TAG, "provider download failed", e);
            String msg = dev.medveed.safeshare.util.ErrorMessages.describe(this, e);
            try {
                android.provider.DocumentsContract.deleteDocument(getContentResolver(), output);
            } catch (Exception ignored) { }
            finishFailed(dao, rowId, msg);
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private void finishFailed(TransferDao dao, long rowId, String msg) {
        dao.setStatus(rowId, TransferEntity.STATUS_FAILED, msg);
        DownloadController.get().post(new DownloadController.State(
                DownloadController.Stage.FAILED, 0, 0, null, null, null, msg, rowId));
        notificationManager.notify(NOTIFICATION_ID,
                makeFailedNotification(msg).build());
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private void updateNotification(int pct) {
        notificationBuilder
                .setContentText(pct + "%")
                .setProgress(100, pct, false);
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private NotificationCompat.Builder makeNotification(int pct) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.download_notification_title))
                .setContentText(pct + "%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setProgress(100, pct, false);
    }

    private NotificationCompat.Builder makeDoneNotification(String filename) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.download_notification_done, filename))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(pi);
    }

    private NotificationCompat.Builder makeFailedNotification(String reason) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.download_notification_failed))
                .setContentText(reason)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(pi);
    }

    private void createChannelIfNeeded() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.download_channel_desc));
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
}
