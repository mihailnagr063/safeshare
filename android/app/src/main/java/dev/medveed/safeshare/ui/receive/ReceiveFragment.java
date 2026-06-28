package dev.medveed.safeshare.ui.receive;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.util.Log;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import dev.medveed.safeshare.util.SnackbarUtil;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.widget.TextView;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.crypto.TransferCodeV2;
import dev.medveed.safeshare.net.ApiClient;
import dev.medveed.safeshare.net.ApiService;
import dev.medveed.safeshare.service.DownloadController;
import dev.medveed.safeshare.service.DownloadService;
import dev.medveed.safeshare.util.NetworkUtil;
import retrofit2.Call;
import retrofit2.Response;

public class ReceiveFragment extends Fragment {

    private static final String TAG = "ReceiveFragment";

    private View groupInput;
    private View groupProgress;
    private View groupDone;
    private MaterialButton buttonStart;
    private MaterialButton buttonScan;
    private MaterialButton buttonPaste;
    private MaterialButton buttonOpen;
    private MaterialButton buttonOpenFolder;
    private MaterialButton buttonNew;
    private TextInputEditText editCode;
    private TextView textProgress;
    private TextView textFilename;
    private TextView textOriginalFilename;
    private TextView textError;
    private LinearProgressIndicator progress;

    @Nullable private View root;
    @Nullable private String pendingCode;
    @Nullable private Uri savedOutputUri;

    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private ActivityResultLauncher<String> createDocumentLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    private ExecutorService executor;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        executor = Executors.newSingleThreadExecutor();

        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result == null || result.getContents() == null) return;
            if (editCode != null) editCode.setText(result.getContents());
        });

        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContract<String, Uri>() {
                    @NonNull
                    @Override
                    public Intent createIntent(@NonNull Context context, @NonNull String input) {
                        String mime = "*/*";
                        String ext = MimeTypeMap.getFileExtensionFromUrl(input);
                        if (ext != null && !ext.isEmpty()) {
                            String guessed = MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(ext.toLowerCase(Locale.US));
                            if (guessed != null) mime = guessed;
                        }
                        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .setType(mime)
                                .putExtra(Intent.EXTRA_TITLE, input);
                    }
                    @Override
                    public Uri parseResult(int resultCode, @Nullable Intent intent) {
                        if (intent == null || resultCode != Activity.RESULT_OK) return null;
                        return intent.getData();
                    }
                },
                uri -> {
                    if (uri == null || pendingCode == null) return;
                    String actualName = null;
                    try (android.database.Cursor c = requireContext().getContentResolver().query(
                            uri, null, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            int idx = c.getColumnIndex(
                                    android.provider.OpenableColumns.DISPLAY_NAME);
                            if (idx >= 0) actualName = c.getString(idx);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not query display name for output URI", e);
                    }
                    DownloadService.start(requireContext(), pendingCode, uri, actualName);
                    pendingCode = null;
                });

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) launchScanner();
                    else if (root != null)
                    SnackbarUtil.show(this, root, R.string.recv_camera_permission_denied, Snackbar.LENGTH_LONG);
                });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
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
        root = v;
        groupInput = v.findViewById(R.id.group_input);
        groupProgress = v.findViewById(R.id.group_progress);
        groupDone = v.findViewById(R.id.group_done);
        buttonStart = v.findViewById(R.id.button_start);
        buttonScan = v.findViewById(R.id.button_scan);
        buttonPaste = v.findViewById(R.id.button_paste);
        buttonOpen = v.findViewById(R.id.button_open);
        buttonOpenFolder = v.findViewById(R.id.button_open_folder);
        buttonNew = v.findViewById(R.id.button_new);
        editCode = v.findViewById(R.id.edit_code);
        textProgress = v.findViewById(R.id.text_progress);
        textFilename = v.findViewById(R.id.text_filename);
        textOriginalFilename = v.findViewById(R.id.text_original_filename);
        textError = v.findViewById(R.id.text_error);
        progress = v.findViewById(R.id.progress);

        buttonScan.setOnClickListener(x -> onScanClicked());
        buttonPaste.setOnClickListener(x -> onPasteClicked());
        buttonStart.setOnClickListener(x -> onStartClicked());
        buttonOpen.setOnClickListener(x -> openSavedFile());
        buttonOpenFolder.setOnClickListener(x -> openContainingFolder());
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
        TransferCodeV2 tc;
        try {
            tc = TransferCodeV2.parse(code);
        } catch (Exception e) {
            if (root != null)
                SnackbarUtil.show(this, root, getString(R.string.recv_invalid_code, e.getMessage()), Snackbar.LENGTH_LONG);
            return;
        }
        pendingCode = code;
        buttonStart.setEnabled(false);
        buttonStart.setText(R.string.recv_checking);

        Context ctx = requireContext();
        executor.execute(() -> {
            if (!NetworkUtil.isOnline(ctx)) {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> {
                    buttonStart.setEnabled(true);
                    buttonStart.setText(R.string.recv_start);
                    if (root != null)
                        SnackbarUtil.show(this, root,
                                getString(R.string.err_no_network), Snackbar.LENGTH_LONG);
                });
                return;
            }
            String filename;
            if (tc.filename != null && !tc.filename.isEmpty()) {
                filename = tc.filename;
            } else if ("s".equals(tc.storagePrefix)) {
                try {
                    String baseUrl = TransferCodeV2.extractSafeShareBaseUrl(tc.data);
                    String fileId = TransferCodeV2.extractFileId("s", tc.data);
                    if (fileId == null) { filename = null; }
                    else {
                        ApiService api = baseUrl != null
                                ? ApiClient.createForBaseUrl(baseUrl).service()
                                : ApiClient.get(ctx).service();
                        Call<Void> call = api.head(fileId);
                        Response<Void> resp = call.execute();
                        if (resp.isSuccessful()) {
                            String b64 = resp.headers().get("X-SafeShare-Filename");
                            if (b64 != null && !b64.isEmpty()) {
                                byte[] decoded = Base64.getUrlDecoder().decode(b64);
                                filename = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                            } else {
                                filename = null;
                            }
                        } else {
                            filename = null;
                        }
                    }
                } catch (Exception e) {
                    filename = null;
                }
            } else {
                filename = null;
            }

            final String fName = filename;
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                buttonStart.setEnabled(true);
                buttonStart.setText(R.string.recv_start);
                createDocumentLauncher.launch(fName != null ? fName : "");
            });
        });
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
                if (s.originalFilename != null && !s.originalFilename.isEmpty()
                        && !s.originalFilename.equals(s.recoveredFilename)) {
                    show(textOriginalFilename, true);
                    textOriginalFilename.setText(getString(R.string.recv_done_name_fmt,
                            s.originalFilename));
                } else {
                    show(textOriginalFilename, false);
                }
                textFilename.setText(getString(R.string.recv_saved_as_fmt,
                        s.recoveredFilename != null ? s.recoveredFilename : "?"));
                break;
            case FAILED:
                show(groupInput, true);
                show(groupProgress, false);
                show(groupDone, false);
                show(textError, true);
                textError.setText(getString(R.string.recv_failed,
                        s.error != null ? s.error : getString(R.string.err_unknown)));
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
            if (root != null)
                SnackbarUtil.show(this, root, getString(R.string.recv_no_app_to_open));
        }
    }

    private void openContainingFolder() {
        if (savedOutputUri == null) return;
        try {
            String docId = DocumentsContract.getDocumentId(savedOutputUri);
            int i = docId.lastIndexOf('/');
            if (i > 0) {
                String parentId = docId.substring(0, i);
                Uri parentUri = DocumentsContract.buildDocumentUri(
                        savedOutputUri.getAuthority(), parentId);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(parentUri, DocumentsContract.Document.MIME_TYPE_DIR);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "openContainingFolder failed", e);
        }
        if (root != null)
            SnackbarUtil.show(this, root, getString(R.string.recv_no_app_to_open));
    }

    private void resetToInput() {
        DownloadController.get().reset();
        if (editCode != null) editCode.setText("");
    }

    private static boolean looksLikeCode(String s) {
        s = s.trim();
        if (s.startsWith("sshare://")) s = s.substring("sshare://".length());
        return s.matches("^[a-z]:.+\\.[A-Za-z0-9_-]{40,}$");
    }

    private static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static String humanSize(long bytes) {
        return dev.medveed.safeshare.util.FormatUtil.humanSize(bytes);
    }
}
