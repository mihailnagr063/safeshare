package dev.medveed.safeshare.ui.contacts;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import dev.medveed.safeshare.R;
import dev.medveed.safeshare.db.ContactEntity;

public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ViewHolder> {

    public interface OnContactActionListener {
        void onEdit(ContactEntity contact);
        void onDelete(ContactEntity contact);
    }

    private final List<ContactEntity> contacts;
    private final OnContactActionListener listener;

    public ContactAdapter(List<ContactEntity> contacts, OnContactActionListener listener) {
        this.contacts = contacts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContactEntity contact = contacts.get(position);
        holder.textName.setText(contact.name);

        holder.buttonEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(contact);
        });
        holder.buttonDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(contact);
        });
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textName;
        ImageButton buttonEdit;
        ImageButton buttonDelete;

        ViewHolder(View v) {
            super(v);
            textName = v.findViewById(R.id.text_name);
            buttonEdit = v.findViewById(R.id.button_edit);
            buttonDelete = v.findViewById(R.id.button_delete);
        }
    }
}
