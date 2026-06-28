package dev.medveed.safeshare.ui.contacts;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.util.Log;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import dev.medveed.safeshare.util.SnackbarUtil;
import com.google.android.material.textfield.TextInputEditText;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.List;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.db.AppDatabase;
import dev.medveed.safeshare.db.ContactEntity;
import dev.medveed.safeshare.util.QrUtil;

public class ContactsFragment extends Fragment {

    private static final String TAG = "ContactsFragment";

    private View root;
    private RecyclerView recyclerContacts;
    private TextView textEmpty;
    private MaterialButton buttonAdd;
    private MaterialButton buttonMyQr;
    private MaterialButton buttonInfo;

    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cameraPermissionLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) launchScanner();
                    else if (root != null)
                        SnackbarUtil.show(this, root, getString(R.string.contacts_camera_denied));
                });

        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result == null || result.getContents() == null || root == null) return;
            String content = result.getContents();
            if (content.startsWith("safeshare-pub://")) {
                String b64 = content.substring("safeshare-pub://".length());
                try {
                    byte[] pubKey = android.util.Base64.decode(b64,
                            android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                    if (pubKey.length == 32) {
                        addContactDialog(pubKey);
                    } else {
                        SnackbarUtil.show(this, root, R.string.contacts_invalid_qr);
                    }
                } catch (Exception e) {
                    SnackbarUtil.show(this, root, R.string.contacts_invalid_qr);
                }
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_contacts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        root = v;
        recyclerContacts = v.findViewById(R.id.recycler_contacts);
        textEmpty = v.findViewById(R.id.text_empty);
        buttonAdd = v.findViewById(R.id.button_add_contact);
        buttonMyQr = v.findViewById(R.id.button_my_qr);
        buttonInfo = v.findViewById(R.id.button_contacts_info);

        recyclerContacts.setLayoutManager(new LinearLayoutManager(requireContext()));

        buttonAdd.setOnClickListener(x -> showAddOptions());
        buttonMyQr.setOnClickListener(x -> showMyQr());
        buttonInfo.setOnClickListener(x -> showContactsInfo());

        loadContacts();
    }

    private void loadContacts() {
        Context ctx = requireContext();
        new Thread(() -> {
            List<ContactEntity> contacts = AppDatabase.get(ctx).contactDao().getAll();
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (recyclerContacts == null) return;
                boolean empty = contacts.isEmpty();
                recyclerContacts.setVisibility(empty ? View.GONE : View.VISIBLE);
                textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (!empty) {
                    recyclerContacts.setAdapter(new ContactAdapter(contacts, new ContactAdapter.OnContactActionListener() {
                        @Override
                        public void onEdit(ContactEntity contact) {
                            showRenameDialog(contact);
                        }
                        @Override
                        public void onDelete(ContactEntity contact) {
                            showDeleteConfirmDialog(contact);
                        }
                    }));
                }
            });
        }).start();
    }

    private void showAddOptions() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.contacts_add)
                .setItems(new String[]{
                        getString(R.string.contacts_scan_title),
                        getString(R.string.contacts_enter_key)
                }, (dialog, which) -> {
                    if (which == 0) requestCameraAndScan();
                    else showManualAddDialog();
                })
                .show();
    }

    private void requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchScanner() {
        ScanOptions opts = new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.contacts_scan_title))
                .setOrientationLocked(true);
        scanLauncher.launch(opts);
    }

    private void showManualAddDialog() {
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_add_contact_manual, null);
        TextInputEditText keyInput = dialogView.findViewById(R.id.input_key);
        TextInputEditText nameInput = dialogView.findViewById(R.id.input_name);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.contacts_enter_key)
                .setView(dialogView)
                .setPositiveButton(R.string.contacts_save, (dialog, which) -> {
                    String keyStr = keyInput.getText() != null ? keyInput.getText().toString().trim() : "";
                    String name = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
                    if (name.isEmpty()) {
                        if (root != null)
                            SnackbarUtil.show(this, root, R.string.contacts_name_required);
                        return;
                    }
                    byte[] pubKey = parsePubKey(keyStr);
                    if (pubKey == null) {
                        if (root != null)
                            SnackbarUtil.show(this, root, R.string.contacts_invalid_key);
                        return;
                    }
                    saveContact(name, pubKey);
                })
                .setNegativeButton(R.string.contacts_cancel, null)
                .show();
    }

    @Nullable
    private static byte[] parsePubKey(String s) {
        if (s == null || s.isEmpty()) return null;
        if (s.startsWith("safeshare-pub://")) {
            s = s.substring("safeshare-pub://".length());
        }
        try {
            byte[] decoded = android.util.Base64.decode(s,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            if (decoded.length == 32) return decoded;
        } catch (Exception e) {
            Log.w(TAG, "parsePubKey decode failed", e);
        }
        return null;
    }

    private void saveContact(String name, byte[] pubKey) {
        Context ctx = requireContext();
        new Thread(() -> {
            try {
                int existing = AppDatabase.get(ctx).contactDao().countByPubKey(pubKey);
                if (existing > 0) {
                    if (getActivity() == null) return;
                    requireActivity().runOnUiThread(() -> {
                        if (root != null)
                            SnackbarUtil.show(this, root, R.string.contacts_duplicate_key);
                    });
                    return;
                }
                ContactEntity contact = new ContactEntity();
                contact.name = name;
                contact.pubKey = pubKey;
                contact.addedAt = System.currentTimeMillis();
                AppDatabase.get(ctx).contactDao().insert(contact);
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(this::loadContacts);
            } catch (Exception e) {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (root != null)
                        SnackbarUtil.show(this, root, R.string.contacts_duplicate_key);
                });
            }
        }).start();
    }

    private void addContactDialog(byte[] pubKey) {
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_contact_name, null);
        TextInputEditText nameInput = dialogView.findViewById(R.id.input_name);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.contacts_new_contact)
                .setView(dialogView)
                .setPositiveButton(R.string.contacts_save, (dialog, which) -> {
                    String name = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
                    if (name.isEmpty()) {
                        if (root != null)
                            SnackbarUtil.show(this, root, R.string.contacts_name_required);
                        return;
                    }
                    saveContact(name, pubKey);
                })
                .setNegativeButton(R.string.contacts_cancel, null)
                .show();
    }

    private void showRenameDialog(ContactEntity contact) {
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_contact_name, null);
        TextInputEditText nameInput = dialogView.findViewById(R.id.input_name);
        nameInput.setText(contact.name);
        nameInput.setSelection(contact.name.length());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.contacts_edit_title)
                .setView(dialogView)
                .setPositiveButton(R.string.contacts_save, (dialog, which) -> {
                    String newName = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
                    if (newName.isEmpty()) {
                        if (root != null)
                            SnackbarUtil.show(this, root, R.string.contacts_name_required);
                        return;
                    }
                    Context ctx = requireContext();
                    new Thread(() -> {
                        contact.name = newName;
                        AppDatabase.get(ctx).contactDao().update(contact);
                        if (getActivity() == null) return;
                        requireActivity().runOnUiThread(this::loadContacts);
                    }).start();
                })
                .setNegativeButton(R.string.contacts_cancel, null)
                .show();
    }

    private void showDeleteConfirmDialog(ContactEntity contact) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.contacts_delete_title)
                .setMessage(getString(R.string.contacts_delete_confirm_msg, contact.name))
                .setPositiveButton(R.string.contacts_delete, (dialog, which) -> {
                    Context ctx = requireContext();
                    new Thread(() -> {
                        AppDatabase.get(ctx).contactDao().delete(contact);
                        if (getActivity() == null) return;
                        requireActivity().runOnUiThread(this::loadContacts);
                    }).start();
                })
                .setNegativeButton(R.string.contacts_cancel, null)
                .show();
    }

    private void showMyQr() {
        SharedPreferences prefs = requireContext().getSharedPreferences("identity", 0);
        String pubB64 = prefs.getString("pub_key", null);
        if (pubB64 == null) {
            if (root != null)
                SnackbarUtil.show(this, root, R.string.contacts_no_identity);
            return;
        }
        String uri = "safeshare-pub://" + pubB64;
        Bitmap qr = QrUtil.renderQr(uri);
        if (qr == null) {
            if (root != null)
                SnackbarUtil.show(this, root, R.string.contacts_qr_failed);
            return;
        }

        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_my_qr, null);
        ((ImageView) dialogView.findViewById(R.id.image_qr)).setImageBitmap(qr);
        ((android.widget.TextView) dialogView.findViewById(R.id.text_uri)).setText(uri);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.contacts_my_qr)
                .setView(dialogView)
                .setPositiveButton(R.string.contacts_close, null)
                .show();

        dialogView.findViewById(R.id.button_copy).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("SafeShare public key", uri));
            if (root != null)
                SnackbarUtil.show(this, root, R.string.contacts_key_copied);
        });

        dialogView.findViewById(R.id.button_share).setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, uri);
            startActivity(Intent.createChooser(share, null));
        });
    }



    private void showContactsInfo() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.info_title_contacts)
                .setMessage(R.string.info_body_contacts)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
