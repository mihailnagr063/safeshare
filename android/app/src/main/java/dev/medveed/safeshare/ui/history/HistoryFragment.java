package dev.medveed.safeshare.ui.history;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.db.AppDatabase;
import dev.medveed.safeshare.db.TransferEntity;

public class HistoryFragment extends Fragment {

    private TransferAdapter adapter;
    private TextView empty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        empty = v.findViewById(R.id.empty);
        RecyclerView list = v.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TransferAdapter(this::onItemClick);
        list.setAdapter(adapter);

        AppDatabase.get(requireContext()).transferDao().observeAll()
                .observe(getViewLifecycleOwner(), items -> {
                    boolean isEmpty = items == null || items.isEmpty();
                    empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                    list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                    adapter.submitList(items);
                });
    }

    private void onItemClick(@NonNull TransferEntity it) {
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        String payload;
        String toast;
        if (it.direction == TransferEntity.DIRECTION_SEND && it.fileId != null) {
            payload = it.fileId;
            toast = getString(R.string.history_copied_file_id);
        } else if (it.filename != null && !it.filename.isEmpty()) {
            payload = it.filename;
            toast = getString(R.string.history_copied_filename);
        } else {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("SafeShare", payload));
        Toast.makeText(requireContext(), toast, Toast.LENGTH_SHORT).show();
    }
}
