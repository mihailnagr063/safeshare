package dev.medveed.safeshare.ui.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.db.TransferEntity;

class TransferAdapter extends ListAdapter<TransferEntity, TransferAdapter.VH> {

    interface OnClick {
        void onClick(@NonNull TransferEntity item);
    }

    private final OnClick onClick;

    TransferAdapter(@NonNull OnClick onClick) {
        super(DIFF);
        this.onClick = onClick;
    }

    private static final DiffUtil.ItemCallback<TransferEntity> DIFF =
            new DiffUtil.ItemCallback<TransferEntity>() {
                @Override public boolean areItemsTheSame(
                        @NonNull TransferEntity a, @NonNull TransferEntity b) {
                    return a.id == b.id;
                }
                @Override public boolean areContentsTheSame(
                        @NonNull TransferEntity a, @NonNull TransferEntity b) {
                    return a.status == b.status
                            && a.expiresAt == b.expiresAt
                            && eq(a.filename, b.filename)
                            && eq(a.fileId, b.fileId);
                }
                private boolean eq(String x, String y) {
                    return x == null ? y == null : x.equals(y);
                }
            };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transfer, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        TransferEntity item = getItem(position);
        h.bind(item, onClick);
    }

    static class VH extends RecyclerView.ViewHolder {
        private final TextView direction, status, date, filename, meta;

        VH(@NonNull View itemView) {
            super(itemView);
            direction = itemView.findViewById(R.id.text_direction);
            status = itemView.findViewById(R.id.text_status);
            date = itemView.findViewById(R.id.text_date);
            filename = itemView.findViewById(R.id.text_filename);
            meta = itemView.findViewById(R.id.text_meta);
        }

        void bind(@NonNull TransferEntity it, @NonNull OnClick onClick) {
            direction.setText(it.direction == TransferEntity.DIRECTION_SEND
                    ? R.string.history_dir_sent : R.string.history_dir_received);
            int statusRes;
            switch (it.status) {
                case TransferEntity.STATUS_IN_PROGRESS:
                    statusRes = R.string.history_status_in_progress; break;
                case TransferEntity.STATUS_DONE:
                    statusRes = R.string.history_status_done; break;
                case TransferEntity.STATUS_FAILED:
                    statusRes = R.string.history_status_failed; break;
                case TransferEntity.STATUS_REVOKED:
                    statusRes = R.string.history_status_revoked; break;
                default:
                    statusRes = R.string.history_status_unknown;
            }
            status.setText(statusRes);
            date.setText(DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(it.createdAt)));
            filename.setText(it.filename == null || it.filename.isEmpty()
                    ? "(unknown)" : it.filename);
            String fid = it.fileId == null || it.fileId.isEmpty() ? "—" : it.fileId;
            String size = humanSize(it.sizeBytes);
            meta.setText(itemView.getContext().getString(
                    R.string.history_meta_fmt, fid, size));
            itemView.setOnClickListener(v -> onClick.onClick(it));
        }

        private static String humanSize(long bytes) {
            if (bytes <= 0) return "?";
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024)
                return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024)
                return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024));
            return String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
