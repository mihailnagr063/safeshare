package dev.medveed.safeshare.ui.onboarding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import dev.medveed.safeshare.R;

public final class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.PageHolder> {

    private final PageData[] pages;

    public OnboardingAdapter(PageData[] pages) {
        this.pages = pages;
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.page_onboarding, parent, false);
        return new PageHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        PageData page = pages[position];
        holder.icon.setImageResource(page.iconResId);
        holder.title.setText(page.titleResId);
        holder.description.setText(page.descResId);
    }

    @Override
    public int getItemCount() {
        return pages.length;
    }

    static class PageHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView description;

        PageHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            title = v.findViewById(R.id.title);
            description = v.findViewById(R.id.description);
        }
    }
}
