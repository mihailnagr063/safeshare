package dev.medveed.safeshare.ui.receive;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.Locale;

import android.widget.TextView;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.crypto.TransferCode;
import dev.medveed.safeshare.service.DownloadController;
import dev.medveed.safeshare.service.DownloadService;

public class ReceiveFragment extends Fragment {

    // Views
    private View groupInput;
    private View groupProgress;
    private View groupDone;
    private MaterialButton buttonStart;
    private MaterialButton buttonScan;
    private MaterialButton buttonPaste;
    private MaterialButton buttonOpen;
    private MaterialButton buttonNew;
    private TextInputEditText editCode;
    private TextView textProgress;
    private TextView textFilename;
    private TextView textError;
    private LinearProgressIndicator progress;

    @Nullable private String pendingCode;
    @Nullable private Uri savedOutputUri;

    // Launchers
    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private ActivityResultLauncher<String> createDocumentLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result == null || result.getContents() == null) return;
            if (editCode != null) editCode.setText(result.getContents());
        });

        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                uri -> {
                    if (uri == null || pendingCode == null) return;
                    DownloadService.start(requireContext(), pendingCode, uri);
                    pendingCode = null;
                });

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) launchScanner();
                    else Toast.makeText(requireContext(),
                            R.string.recv_camera_permission_denied,
                            Toast.LENGTH_LONG).show();
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_receive, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        groupInput = v.findViewById(R.id.group_input);
        groupProgress = v.findViewById(R.id.group_progress);
        groupDone = v.findViewById(R.id.group_done);
        buttonStart = v.findViewById(R.id.button_start);
        buttonScan = v.findViewById(R.id.button_scan);
        buttonPaste = v.findViewById(R.id.button_paste);
        buttonOpen = v.findViewById(R.id.button_open);
        buttonNew = v.findViewById(R.id.button_new);
        editCode = v.findViewById(R.id.edit_code);
        textProgress = v.findViewById(R.id.text_progress);
        textFilename = v.findViewById(R.id.text_filename);
        textError = v.findViewById(R.id.text_error);
        progress = v.findViewById(R.id.progress);

        buttonScan.setOnClickListener(x -> onScanClicked());
        buttonPaste.setOnClickListener(x -> onPasteClicked());
        buttonStart.setOnClickListener(x -> onStartClicked());
        buttonOpen.setOnClickListener(x -> openSavedFile());
        buttonNew.setOnClickListener(x -> resetToInput());

        editCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                buttonStart.setEnabled(looksLikeCode(s.toString()));
            }
        });

        DownloadController.get().state().observe(getViewLifecycleOwner(),
                this::onStateChanged);

        Bundle args = getArguments();
        if (args != null) {
            String prefill = args.getString(dev.medveed.safeshare.MainActivity.EXTRA_PREFILL_CODE);
            if (prefill != null && !prefill.isEmpty()) {
                editCode.setText(prefill);
            }
        }
    }

    private void onScanClicked() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchScanner() {
        ScanOptions opts = new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("")
                .setBeepEnabled(false)
                .setOrientationLocked(true);
        scanLauncher.launch(opts);
    }

    private void onPasteClicked() {
        ClipboardManager cm = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData data = cm.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) return;
        ClipDescription desc = data.getDescription();
        if (desc == null || !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return;
        CharSequence text = data.getItemAt(0).getText();
        if (text != null) editCode.setText(text);
    }

    private void onStartClicked() {
        String code = editCode.getText() == null ? "" : editCode.getText().toString().trim();
        try {
            TransferCode.parseAny(code);
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "Invalid code: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        pendingCode = code;
        String suggested = "safeshare_" + System.currentTimeMillis() + ".bin";
        createDocumentLauncher.launch(suggested);
    }

    private void onStateChanged(DownloadController.State s) {
        switch (s.stage) {
            case IDLE:
                show(groupInput, true);
                show(groupProgress, false);
                show(groupDone, false);
                show(textError, false);
                savedOutputUri = null;
                break;
            case DOWNLOADING: {
                show(groupInput, false);
                show(groupProgress, true);
                show(groupDone, false);
                show(textError, false);
                int pct = s.bytesTotal > 0
                        ? (int) (s.bytesDone * 100 / s.bytesTotal) : 0;
                progress.setIndeterminate(s.bytesTotal <= 0);
                progress.setProgress(pct);
                textProgress.setText(getString(R.string.recv_progress_fmt,
                        pct, humanSize(s.bytesDone), humanSize(s.bytesTotal)));
                break;
            }
            case DONE:
                show(groupInput, false);
                show(groupProgress, false);
                show(groupDone, true);
                show(textError, false);
                savedOutputUri = s.output;
                textFilename.setText(getString(R.string.recv_done_name_fmt,
                        s.recoveredFilename != null ? s.recoveredFilename : "?"));
                break;
            case FAILED:
                show(groupInput, true);
                show(groupProgress, false);
                show(groupDone, false);
                show(textError, true);
                textError.setText(getString(R.string.recv_failed,
                        s.error != null ? s.error : "unknown"));
                break;
        }
    }

    private void openSavedFile() {
        if (savedOutputUri == null) return;
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setData(savedOutputUri);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "No app can open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetToInput() {
        DownloadController.get().reset();
        if (editCode != null) editCode.setText("");
    }

    private static boolean looksLikeCode(String s) {
        s = s.trim();
        if (s.startsWith("sshare://")) s = s.substring("sshare://".length());
        int dot = s.indexOf('.');
        if (dot <= 0) return false;
        if (dot != 8) return false;
        String rest = s.substring(dot + 1).trim();
        if (rest.isEmpty()) return false;
        if (!rest.contains(" ")) return rest.length() >= 20;
        return rest.split("\\s+").length >= 2;
    }

    private static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
