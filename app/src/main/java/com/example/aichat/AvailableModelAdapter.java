package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AvailableModelAdapter extends RecyclerView.Adapter<AvailableModelAdapter.Holder> {
    private List<ProviderInfo.ProviderModelInfo> items = new ArrayList<>();
    private Set<String> addedIds = new HashSet<>();
    private OnAddListener listener;

    public interface OnAddListener {
        void onAdd(ProviderInfo.ProviderModelInfo model);
    }

    public void setOnAddListener(OnAddListener l) {
        listener = l;
    }

    public void setItems(List<ProviderInfo.ProviderModelInfo> list, Set<String> alreadyAdded) {
        items = list != null ? new ArrayList<>(list) : new ArrayList<>();
        addedIds = alreadyAdded != null ? new HashSet<>(alreadyAdded) : new HashSet<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_model_available, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ProviderInfo.ProviderModelInfo m = items.get(position);
        h.modelId.setText(m.modelId);
        boolean alreadyAdded = addedIds.contains(m.modelId);
        h.btnAdd.setEnabled(!alreadyAdded);
        h.btnAdd.setText(alreadyAdded ? "已添加" : "添加");
        h.btnAdd.setOnClickListener(v -> {
            if (!alreadyAdded && listener != null) listener.onAdd(m);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView modelId;
        MaterialButton btnAdd;

        Holder(View v) {
            super(v);
            modelId = v.findViewById(R.id.modelId);
            btnAdd = v.findViewById(R.id.btnAdd);
        }
    }
}
