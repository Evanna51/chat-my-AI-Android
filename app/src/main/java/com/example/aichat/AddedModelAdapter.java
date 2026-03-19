package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class AddedModelAdapter extends RecyclerView.Adapter<AddedModelAdapter.Holder> {
    private List<ProviderInfo.ProviderModelInfo> items = new ArrayList<>();
    private OnModelActionListener listener;

    public interface OnModelActionListener {
        void onEditAlias(ProviderInfo.ProviderModelInfo model);
        void onRemove(ProviderInfo.ProviderModelInfo model);
    }

    public void setOnModelActionListener(OnModelActionListener l) {
        listener = l;
    }

    public void setModels(List<ProviderInfo.ProviderModelInfo> list) {
        items = list != null ? new ArrayList<>(list) : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_model_added, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ProviderInfo.ProviderModelInfo m = items.get(position);
        String display = m.nickname != null && !m.nickname.isEmpty() ? m.nickname + " (" + m.modelId + ")" : m.modelId;
        h.modelDisplay.setText(display);
        h.btnEditAlias.setOnClickListener(v -> {
            if (listener != null) listener.onEditAlias(m);
        });
        h.btnRemove.setOnClickListener(v -> {
            if (listener != null) listener.onRemove(m);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView modelDisplay;
        MaterialButton btnEditAlias, btnRemove;

        Holder(View v) {
            super(v);
            modelDisplay = v.findViewById(R.id.modelDisplay);
            btnEditAlias = v.findViewById(R.id.btnEditAlias);
            btnRemove = v.findViewById(R.id.btnRemove);
        }
    }
}
