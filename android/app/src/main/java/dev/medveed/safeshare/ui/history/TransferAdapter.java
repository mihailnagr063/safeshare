package dev.medveed.safeshare.ui.history;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
                            && eq(a.fileId, b.fileId)
                            && eq(a.storagePrefix, b.storagePrefix)
                            && eq(a.savedUri, b.savedUri);
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
        private final ImageView iconOpen;

        VH(@NonNull View itemView) {
            super(itemView);
            direction = itemView.findViewById(R.id.text_direction);
            status = itemView.findViewById(R.id.text_status);
            date = itemView.findViewById(R.id.text_date);
            filename = itemView.findViewById(R.id.text_filename);
            meta = itemView.findViewById(R.id.text_meta);
            iconOpen = itemView.findViewById(R.id.icon_open);
        }

        void bind(@NonNull TransferEntity it, @NonNull OnClick onClick) {
            direction.setText(it.direction == TransferEntity.DIRECTION_SEND
                    ? R.string.history_dir_sent : R.string.history_dir_received);
            int statusRes;
            int statusColor;
            switch (it.status) {
                case TransferEntity.STATUS_IN_PROGRESS:
                    statusRes = R.string.history_status_in_progress;
                    statusColor = android.R.color.holo_orange_dark;
                    break;
                case TransferEntity.STATUS_DONE:
                    statusRes = R.string.history_status_done;
                    statusColor = android.R.color.holo_green_dark;
                    break;
                case TransferEntity.STATUS_FAILED:
                    statusRes = R.string.history_status_failed;
                    statusColor = android.R.color.holo_red_dark;
                    break;
                case TransferEntity.STATUS_REVOKED:
                    statusRes = R.string.history_status_revoked;
                    statusColor = android.R.color.holo_red_light;
                    break;
                default:
                    statusRes = R.string.history_status_unknown;
                    statusColor = android.R.color.darker_gray;
            }
            status.setText(statusRes);
            status.setTextColor(itemView.getContext().getColor(statusColor));

            date.setText(DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(it.createdAt)));
            filename.setText(it.filename == null || it.filename.isEmpty()
                    ? itemView.getContext().getString(R.string.history_unknown_name) : it.filename);
            String size = humanSize(it.sizeBytes);
            String metaText;
            if (it.storagePrefix == null || it.storagePrefix.isEmpty()
                    || "s".equals(it.storagePrefix)) {
                String fid = it.fileId == null || it.fileId.isEmpty()
                        ? itemView.getContext().getString(R.string.history_missing_id) : it.fileId;
                metaText = itemView.getContext().getString(
                        R.string.history_meta_fmt, fid, size);
            } else {
                String providerName = providerDisplayName(itemView.getContext(), it.storagePrefix);
                metaText = providerName + " · " + size;
            }
            meta.setText(metaText);

            boolean canOpen = it.direction == TransferEntity.DIRECTION_RECEIVE
                    && it.status == TransferEntity.STATUS_DONE
                    && it.savedUri != null && !it.savedUri.isEmpty();
            iconOpen.setVisibility(canOpen ? View.VISIBLE : View.GONE);

            itemView.setOnClickListener(v -> onClick.onClick(it));
        }

        private static String humanSize(long bytes) {
            return dev.medveed.safeshare.util.FormatUtil.humanSize(bytes);
        }

        private static String providerDisplayName(Context ctx, String prefix) {
            if ("y".equals(prefix)) {
                return ctx.getString(R.string.history_provider_yandex);
            }
            return ctx.getString(R.string.history_provider_unknown, prefix);
        }
    }
}
