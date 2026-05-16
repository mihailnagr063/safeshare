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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.medveed.safeshare.MainActivity;
import dev.medveed.safeshare.R;
import dev.medveed.safeshare.crypto.TransferCode;
import dev.medveed.safeshare.db.AppDatabase;
import dev.medveed.safeshare.db.TransferDao;
import dev.medveed.safeshare.db.TransferEntity;
import dev.medveed.safeshare.net.ApiClient;
import dev.medveed.safeshare.net.ApiService;
import dev.medveed.safeshare.net.StreamingDownloadDecryptor;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final String CHANNEL_ID = "safeshare_download";
    private static final int NOTIFICATION_ID = 102;

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_OUTPUT_URI = "output_uri";

    private ExecutorService executor;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManager notificationManager;

    public static void start(Context context, String transferCode, Uri outputUri) {
        Intent i = new Intent(context, DownloadService.class);
        i.putExtra(EXTRA_CODE, transferCode);
        i.putExtra(EXTRA_OUTPUT_URI, outputUri);
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
        if (code == null || output == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        notificationBuilder = makeNotification(0);
        startForeground(NOTIFICATION_ID, notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        final String fCode = code;
        final Uri fOutput = output;
        executor.execute(() -> runDownload(fCode, fOutput));
        return START_NOT_STICKY;
    }

    private void runDownload(String codeStr, Uri output) {
        TransferDao dao = AppDatabase.get(this).transferDao();
        TransferEntity row = new TransferEntity();
        row.direction = TransferEntity.DIRECTION_RECEIVE;
        row.createdAt = System.currentTimeMillis();
        row.status = TransferEntity.STATUS_IN_PROGRESS;
        row.filename = "";
        long rowId = dao.insert(row);

        TransferCode code;
        try {
            code = TransferCode.parseAny(codeStr);
        } catch (Exception e) {
            finishFailed(dao, rowId, "Invalid transfer code: " + e.getMessage());
            return;
        }

        String fileId = dev.medveed.safeshare.crypto.CrockfordBase32.encode5(code.fileId);

        DownloadController.get().post(new DownloadController.State(
                DownloadController.Stage.DOWNLOADING, 0, 0,
                null, null, null, rowId));

        ApiService api = ApiClient.get(this).service();
        try {
            Call<ResponseBody> call = api.download(fileId);
            Response<ResponseBody> resp = call.execute();
            if (!resp.isSuccessful() || resp.body() == null) {
                String msg = "HTTP " + resp.code();
                if (resp.code() == 410) msg = "File has expired or reached its download limit.";
                if (resp.code() == 404) msg = "File not found. The code may be wrong.";
                try (ResponseBody err = resp.errorBody()) {
                    if (err != null) {
                        String body = err.string();
                        if (!body.isEmpty() && resp.code() >= 500) msg += " (" + body + ")";
                    }
                } catch (IOException ignored) { /* ok */ }
                throw new IOException(msg);
            }
            long cipherTotal = resp.body().contentLength();
            String filename;
            try (ResponseBody body = resp.body();
                 InputStream in = body.byteStream();
                 OutputStream out = getContentResolver().openOutputStream(output)) {
                if (out == null) throw new IOException("cannot open output URI");
                filename = StreamingDownloadDecryptor.decrypt(
                        code.km.key, code.km.r, cipherTotal,
                        in, out,
                        (done, total) -> {
                            DownloadController.get().post(new DownloadController.State(
                                    DownloadController.Stage.DOWNLOADING, done, total,
                                    null, null, null, rowId));
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            updateNotification(pct);
                        });
            }

            TransferEntity existing = dao.byId(rowId);
            if (existing != null) {
                existing.fileId = fileId;
                existing.filename = filename;
                existing.status = TransferEntity.STATUS_DONE;
                dao.update(existing);
            }

            DownloadController.get().post(new DownloadController.State(
                    DownloadController.Stage.DONE,
                    cipherTotal > 0 ? cipherTotal : 1,
                    cipherTotal > 0 ? cipherTotal : 1,
                    filename, output, null, rowId));

            notificationManager.notify(NOTIFICATION_ID,
                    makeDoneNotification(filename).build());
        } catch (Exception e) {
            Log.w(TAG, "download failed", e);
            String msg = dev.medveed.safeshare.util.ErrorMessages.describe(this, e);
            // Attempt to remove the (probably garbage) output file.
            try {
                android.database.Cursor c = getContentResolver().query(output, null, null, null, null);
                if (c != null) c.close();
                android.provider.DocumentsContract.deleteDocument(getContentResolver(), output);
            } catch (Exception ignored) { /* best-effort */ }
            finishFailed(dao, rowId, msg);
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private void finishFailed(TransferDao dao, long rowId, String msg) {
        dao.setStatus(rowId, TransferEntity.STATUS_FAILED, msg);
        DownloadController.get().post(new DownloadController.State(
                DownloadController.Stage.FAILED, 0, 0, null, null, msg, rowId));
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
