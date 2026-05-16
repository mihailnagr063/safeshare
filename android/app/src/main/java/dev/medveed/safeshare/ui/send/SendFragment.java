package dev.medveed.safeshare.ui.send;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import android.widget.ImageView;
import android.widget.TextView;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.service.UploadController;
import dev.medveed.safeshare.service.UploadService;


public class SendFragment extends Fragment {

    @Nullable private Uri pickedUri;
    @Nullable private String pickedName;
    private long pickedSize;
    private long ttlSeconds = 24 * 3600;

    private ActivityResultLauncher<String[]> pickLauncher;

    // Views
    private View groupPicker;
    private View groupProgress;
    private View groupDone;
    private MaterialButton buttonPick;
    private MaterialButton buttonStart;
    private MaterialButton buttonCopy;
    private MaterialButton buttonShare;
    private TextView textSelection;
    private TextView textMaxDownloads;
    private TextView textProgress;
    private TextView textCode;
    private TextView textExpires;
    private TextView textError;
    private Slider sliderMax;
    private MaterialButtonToggleGroup groupTtl;
    private LinearProgressIndicator progress;
    private ImageView imageQr;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onFilePicked);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_send, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        groupPicker = v.findViewById(R.id.group_picker);
        groupProgress = v.findViewById(R.id.group_progress);
        groupDone = v.findViewById(R.id.group_done);
        buttonPick = v.findViewById(R.id.button_pick);
        buttonStart = v.findViewById(R.id.button_start);
        buttonCopy = v.findViewById(R.id.button_copy);
        buttonShare = v.findViewById(R.id.button_share);
        textSelection = v.findViewById(R.id.text_selection);
        textMaxDownloads = v.findViewById(R.id.text_max_downloads);
        textProgress = v.findViewById(R.id.text_progress);
        textCode = v.findViewById(R.id.text_code);
        textExpires = v.findViewById(R.id.text_expires);
        textError = v.findViewById(R.id.text_error);
        sliderMax = v.findViewById(R.id.slider_max_downloads);
        groupTtl = v.findViewById(R.id.group_ttl);
        progress = v.findViewById(R.id.progress);
        imageQr = v.findViewById(R.id.image_qr);

        buttonPick.setOnClickListener(x -> pickLauncher.launch(new String[]{"*/*"}));
        buttonStart.setOnClickListener(x -> startUpload());
        buttonCopy.setOnClickListener(x -> copyCode());
        buttonShare.setOnClickListener(x -> shareCode());

        View groupCustomTtl = v.findViewById(R.id.group_custom_ttl);
        com.google.android.material.textfield.TextInputEditText editTtlValue =
                v.findViewById(R.id.edit_ttl_value);
        MaterialButtonToggleGroup groupTtlUnit = v.findViewById(R.id.group_ttl_unit);
        groupTtlUnit.check(R.id.ttl_unit_hr);

        groupTtl.check(R.id.ttl_24h);
        groupTtl.addOnButtonCheckedListener((grp, id, checked) -> {
            if (!checked) return;
            if (id == R.id.ttl_1h) { ttlSeconds = 3600; groupCustomTtl.setVisibility(View.GONE); }
            else if (id == R.id.ttl_24h) { ttlSeconds = 24 * 3600; groupCustomTtl.setVisibility(View.GONE); }
            else if (id == R.id.ttl_7d) { ttlSeconds = 7 * 24 * 3600; groupCustomTtl.setVisibility(View.GONE); }
            else if (id == R.id.ttl_custom) { groupCustomTtl.setVisibility(View.VISIBLE); recalcCustomTtl(editTtlValue, groupTtlUnit); }
        });

        if (editTtlValue != null) {
            editTtlValue.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    if (groupTtl.getCheckedButtonId() == R.id.ttl_custom)
                        recalcCustomTtl(editTtlValue, groupTtlUnit);
                }
            });
        }
        groupTtlUnit.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked && groupTtl.getCheckedButtonId() == R.id.ttl_custom)
                recalcCustomTtl(editTtlValue, groupTtlUnit);
        });

        sliderMax.addOnChangeListener((s, value, fromUser) ->
                textMaxDownloads.setText(getString(
                        R.string.send_max_downloads, (int) value)));
        textMaxDownloads.setText(getString(
                R.string.send_max_downloads, (int) sliderMax.getValue()));

        UploadController.get().state().observe(getViewLifecycleOwner(), this::onStateChanged);
    }

    private void onFilePicked(@Nullable Uri uri) {
        if (uri == null) return;
        pickedUri = uri;
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {

        }
        Context ctx = requireContext();
        pickedName = queryDisplayName(ctx, uri);
        pickedSize = queryDisplaySize(ctx, uri);
        textSelection.setText(getString(R.string.send_selected,
                pickedName == null ? "?" : pickedName,
                humanSize(pickedSize)));
        buttonStart.setEnabled(pickedName != null && pickedSize > 0);
    }

    private void startUpload() {
        if (pickedUri == null || pickedName == null || pickedSize <= 0) return;
        long maxDownloads = (long) sliderMax.getValue();
        UploadService.start(requireContext(), pickedUri, pickedName, pickedSize,
                ttlSeconds, maxDownloads);
    }

    private void onStateChanged(UploadController.State s) {
        switch (s.stage) {
            case IDLE:
                show(groupPicker, true);
                show(groupProgress, false);
                show(groupDone, false);
                show(textError, false);
                break;
            case UPLOADING: {
                show(groupPicker, false);
                show(groupProgress, true);
                show(groupDone, false);
                show(textError, false);
                int pct = s.bytesTotal > 0
                        ? (int) (s.bytesDone * 100 / s.bytesTotal) : 0;
                progress.setProgress(pct);
                textProgress.setText(getString(R.string.send_progress_fmt,
                        pct, humanSize(s.bytesDone), humanSize(s.bytesTotal)));
                break;
            }
            case DONE:
                show(groupPicker, false);
                show(groupProgress, false);
                show(groupDone, true);
                show(textError, false);
                textCode.setText(s.transferCode != null ? s.transferCode : "");
                textExpires.setText(getString(R.string.send_expires_fmt,
                        DateFormat.getDateTimeInstance().format(new Date(s.expiresAt))));
                // QR uses the compact sshare:// URI (36 chars → small QR).
                String qrContent = s.compactUri != null ? s.compactUri : s.transferCode;
                Bitmap qr = renderQr(qrContent);
                if (qr != null) imageQr.setImageBitmap(qr);
                break;
            case FAILED:
                show(groupPicker, true);
                show(groupProgress, false);
                show(groupDone, false);
                show(textError, true);
                textError.setText(getString(R.string.send_failed,
                        s.error != null ? s.error : "unknown"));
                break;
        }
    }

    private void copyCode() {
        CharSequence code = textCode.getText();
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SafeShare code", code));
        Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show();
    }

    private void shareCode() {
        CharSequence code = textCode.getText();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, code);
        startActivity(Intent.createChooser(intent, null));
    }

    private static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void recalcCustomTtl(
            com.google.android.material.textfield.TextInputEditText editTtlValue,
            MaterialButtonToggleGroup groupTtlUnit
    ) {
        CharSequence cs = editTtlValue.getText();
        if (cs == null || cs.length() == 0) return;
        try {
            long n = Long.parseLong(cs.toString().trim());
            int unitId = groupTtlUnit.getCheckedButtonId();
            long mult;
            if (unitId == R.id.ttl_unit_min) mult = 60;
            else if (unitId == R.id.ttl_unit_day) mult = 24 * 3600;
            else mult = 3600;
            ttlSeconds = Math.max(60, Math.min(n * mult, 7L * 24 * 3600));
        } catch (NumberFormatException ignored) { /* keep previous */ }
    }

    @Nullable
    private static Bitmap renderQr(@Nullable String content) {
        if (content == null || content.isEmpty()) return null;
        int size = 800;
        try {
            BitMatrix matrix = new MultiFormatWriter()
                    .encode(content, BarcodeFormat.QR_CODE, size, size);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int off = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[off + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (WriterException e) {
            return null;
        }
    }

    @Nullable
    private static String queryDisplayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { /* best-effort */ }
        return uri.getLastPathSegment();
    }

    private static long queryDisplaySize(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) return c.getLong(idx);
            }
        } catch (Exception ignored) { /* best-effort */ }
        return -1;
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
