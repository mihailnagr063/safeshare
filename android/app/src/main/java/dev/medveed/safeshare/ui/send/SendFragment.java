package dev.medveed.safeshare.ui.send;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import dev.medveed.safeshare.util.SnackbarUtil;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.db.AppDatabase;
import dev.medveed.safeshare.db.ContactEntity;
import dev.medveed.safeshare.service.UploadController;
import dev.medveed.safeshare.service.UploadService;
import dev.medveed.safeshare.net.storage.StorageProvider;
import dev.medveed.safeshare.net.storage.StorageProviders;


public class SendFragment extends Fragment {

    @Nullable private View root;
    @Nullable private Uri pickedUri;
    @Nullable private String pickedName;
    private long pickedSize;
    private long ttlSeconds = 24 * 3600;
    @Nullable private byte[] recipientPubBytes;
    @Nullable private String recipientName;

    private ActivityResultLauncher<String[]> pickLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<ScanOptions> scanLauncher;

    private View groupPicker;
    private View groupProgress;
    private View groupDone;
    private MaterialButton buttonScanRecipient;
    private MaterialButton buttonPickContact;
    private MaterialButton buttonPick;
    private MaterialButton buttonStart;
    private MaterialButton buttonCopy;
    private MaterialButton buttonShare;
    private MaterialButton buttonNewSend;
    private View cardRecipientInfo;
    private View cardFileInfo;
    private TextView textRecipient;
    private TextView textSelection;
    private TextView textMaxDownloads;
    private TextView textProgress;
    private TextView textCode;
    private TextView textExpires;
    private TextView textError;
    private Slider sliderMax;
    private MaterialButtonToggleGroup groupTtl;
    private MaterialAutoCompleteTextView dropdownStorage;
    private String selectedProviderPrefix;
    private List<StorageProvider> providerList;
    private View groupSafeShareOptions;
    private LinearProgressIndicator progress;
    private ImageView imageQr;
    private MaterialButton buttonSendInfo;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onFilePicked);
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) launchRecipientScanner();
                });
        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result == null || result.getContents() == null) return;
            onRecipientScanned(result.getContents());
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (dropdownStorage != null) refreshStorageDropdown();
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
        root = v;
        groupPicker = v.findViewById(R.id.group_picker);
        groupProgress = v.findViewById(R.id.group_progress);
        groupDone = v.findViewById(R.id.group_done);
        cardRecipientInfo = v.findViewById(R.id.card_recipient_info);
        cardFileInfo = v.findViewById(R.id.card_file_info);
        buttonScanRecipient = v.findViewById(R.id.button_scan_recipient);
        buttonPickContact = v.findViewById(R.id.button_pick_contact);
        buttonPick = v.findViewById(R.id.button_pick);
        buttonStart = v.findViewById(R.id.button_start);
        buttonCopy = v.findViewById(R.id.button_copy);
        buttonShare = v.findViewById(R.id.button_share);
        buttonNewSend = v.findViewById(R.id.button_new_send);
        textRecipient = v.findViewById(R.id.text_recipient);
        textSelection = v.findViewById(R.id.text_selection);
        textMaxDownloads = v.findViewById(R.id.text_max_downloads);
        textProgress = v.findViewById(R.id.text_progress);
        textCode = v.findViewById(R.id.text_code);
        textExpires = v.findViewById(R.id.text_expires);
        textError = v.findViewById(R.id.text_error);
        sliderMax = v.findViewById(R.id.slider_max_downloads);
        groupTtl = v.findViewById(R.id.group_ttl);
        dropdownStorage = v.findViewById(R.id.dropdown_storage);
        groupSafeShareOptions = v.findViewById(R.id.group_safeshare_options);
        progress = v.findViewById(R.id.progress);
        imageQr = v.findViewById(R.id.image_qr);
        buttonSendInfo = v.findViewById(R.id.button_send_info);

        buttonScanRecipient.setOnClickListener(x -> onScanRecipientClicked());
        buttonPickContact.setOnClickListener(x -> onPickContactClicked());
        buttonPick.setOnClickListener(x -> pickLauncher.launch(new String[]{"*/*"}));
        buttonStart.setOnClickListener(x -> startUpload());

        refreshStorageDropdown();

        dropdownStorage.setOnItemClickListener((parent, view, position, id) -> {
            selectedProviderPrefix = providerList.get(position).prefix();
            groupSafeShareOptions.setVisibility(
                    "s".equals(selectedProviderPrefix) ? View.VISIBLE : View.GONE);
        });

        new Thread(() -> {
            int count = AppDatabase.get(requireContext()).contactDao().getAll().size();
            requireActivity().runOnUiThread(() ->
                    buttonPickContact.setEnabled(count > 0));
        }).start();
        buttonCopy.setOnClickListener(x -> copyCode());
        buttonShare.setOnClickListener(x -> shareCode());
        buttonNewSend.setOnClickListener(x -> resetToIdle());
        buttonSendInfo.setOnClickListener(x -> showSendInfo());

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
        cardFileInfo.setVisibility(View.VISIBLE);
        updateStartEnabled();
    }

    private void updateStartEnabled() {
        buttonStart.setEnabled(pickedName != null && pickedSize > 0 && recipientPubBytes != null);
    }

    private void onScanRecipientClicked() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchRecipientScanner();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchRecipientScanner() {
        ScanOptions opts = new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.send_scan_qr_prompt))
                .setOrientationLocked(true);
        scanLauncher.launch(opts);
    }

    private void onRecipientScanned(String content) {
        if (content.startsWith("safeshare-pub://")) {
            String b64 = content.substring("safeshare-pub://".length());
            try {
                recipientPubBytes = android.util.Base64.decode(b64,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                if (recipientPubBytes.length != 32) {
                    recipientPubBytes = null;
                    if (root != null)
                        SnackbarUtil.show(this, root, R.string.contacts_invalid_qr);
                    return;
                }
                recipientName = getString(R.string.send_scanned_contact);
                textRecipient.setText(getString(R.string.send_recipient_label, recipientName));
                cardRecipientInfo.setVisibility(View.VISIBLE);
                updateStartEnabled();
            } catch (Exception e) {
                recipientPubBytes = null;
                if (root != null)
                    SnackbarUtil.show(this, root, R.string.contacts_invalid_qr);
            }
        } else {
            recipientPubBytes = null;
            if (root != null)
                SnackbarUtil.show(this, root, R.string.contacts_invalid_qr);
        }
    }

    private void onPickContactClicked() {
        new Thread(() -> {
            java.util.List<ContactEntity> contacts = AppDatabase.get(requireContext()).contactDao().getAll();
            requireActivity().runOnUiThread(() -> {
                if (contacts.isEmpty()) {
                    if (root != null)
                        SnackbarUtil.show(this, root, R.string.contacts_empty);
                    return;
                }
                String[] names = new String[contacts.size()];
                for (int i = 0; i < contacts.size(); i++) {
                    names[i] = contacts.get(i).name;
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.send_pick_contact)
                        .setItems(names, (dialog, which) -> {
                            ContactEntity c = contacts.get(which);
                            recipientPubBytes = c.pubKey;
                            recipientName = c.name;
                            textRecipient.setText(getString(R.string.send_recipient_label, recipientName));
                            cardRecipientInfo.setVisibility(View.VISIBLE);
                            updateStartEnabled();
                        })
                        .setNegativeButton(R.string.contacts_cancel, null)
                        .show();
            });
        }).start();
    }

    private void refreshStorageDropdown() {
        providerList = new ArrayList<>(StorageProviders.available(requireContext()));
        String[] displayNames = new String[providerList.size()];
        for (int i = 0; i < providerList.size(); i++) {
            displayNames[i] = providerList.get(i).displayName();
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, displayNames);
        dropdownStorage.setAdapter(adapter);

        if (selectedProviderPrefix == null) {
            String defaultPrefix = StorageProviders.getDefault(requireContext()).prefix();
            for (int i = 0; i < providerList.size(); i++) {
                if (providerList.get(i).prefix().equals(defaultPrefix)) {
                    dropdownStorage.setText(displayNames[i], false);
                    selectedProviderPrefix = defaultPrefix;
                    break;
                }
            }
        }
        groupSafeShareOptions.setVisibility("s".equals(selectedProviderPrefix)
                ? View.VISIBLE : View.GONE);
    }

    private void startUpload() {
        if (pickedUri == null || pickedName == null || pickedSize <= 0 || recipientPubBytes == null) return;
        if (selectedProviderPrefix == null) selectedProviderPrefix = "s";
        long maxDownloads = (long) sliderMax.getValue();
        UploadService.start(requireContext(), pickedUri, pickedName, pickedSize,
                ttlSeconds, maxDownloads, recipientPubBytes, selectedProviderPrefix);
    }

    private void onStateChanged(UploadController.State s) {
        switch (s.stage) {
            case IDLE:
                show(groupPicker, true);
                show(groupProgress, false);
                show(groupDone, false);
                show(textError, false);
                buttonSendInfo.setVisibility(View.GONE);
                textSelection.setText(R.string.send_no_file);
                buttonStart.setEnabled(false);
                break;
            case UPLOADING: {
                show(groupPicker, false);
                show(groupProgress, true);
                show(groupDone, false);
                show(textError, false);
                buttonSendInfo.setVisibility(View.GONE);
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
                buttonSendInfo.setVisibility(View.VISIBLE);
                textCode.setText(s.transferCode != null ? s.transferCode : "");
                if ("s".equals(selectedProviderPrefix)) {
                    show(textExpires, true);
                    textExpires.setText(getString(R.string.send_expires_fmt,
                            DateFormat.getDateTimeInstance().format(new Date(s.expiresAt))));
                } else {
                    show(textExpires, false);
                }
                String qrContent = s.compactUri != null ? s.compactUri : s.transferCode;
                Bitmap qr = renderQr(qrContent);
                if (qr != null) imageQr.setImageBitmap(qr);
                break;
            case FAILED:
                show(groupPicker, true);
                show(groupProgress, false);
                show(groupDone, false);
                show(textError, true);
                buttonSendInfo.setVisibility(View.GONE);
                textError.setText(getString(R.string.send_failed,
                        s.error != null ? s.error : getString(R.string.err_unknown)));
                break;
        }
    }

    private void copyCode() {
        CharSequence code = textCode.getText();
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SafeShare code", code));
        if (root != null)
            SnackbarUtil.show(this, root, getString(R.string.send_code_copied));
    }

    private void shareCode() {
        CharSequence code = textCode.getText();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, code);
        startActivity(Intent.createChooser(intent, null));
    }

    private void resetToIdle() {
        pickedUri = null;
        pickedName = null;
        pickedSize = 0;
        recipientPubBytes = null;
        recipientName = null;
        textRecipient.setText(R.string.send_no_recipient);
        cardRecipientInfo.setVisibility(View.GONE);
        textSelection.setText(R.string.send_no_file);
        cardFileInfo.setVisibility(View.GONE);
        buttonStart.setEnabled(false);
        buttonSendInfo.setVisibility(View.GONE);
        UploadController.get().reset();
    }

    private void showSendInfo() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.info_title_transfer_code)
                .setMessage(R.string.info_body_transfer_code)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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
