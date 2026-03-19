package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ProviderListAdapter extends RecyclerView.Adapter<ProviderListAdapter.Holder> {
    private List<ProviderInfo> items = new ArrayList<>();
    private OnProviderClickListener listener;

    public interface OnProviderClickListener {
        void onProviderClick(ProviderInfo p);
    }

    public void setOnProviderClickListener(OnProviderClickListener l) {
        listener = l;
    }

    public void setProviders(List<ProviderInfo> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_provider, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ProviderInfo p = items.get(position);
        h.name.setText(p.name);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onProviderClick(p);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView name;

        Holder(View v) {
            super(v);
            name = v.findViewById(R.id.providerName);
        }
    }
}
