package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ModelListAdapter extends RecyclerView.Adapter<ModelListAdapter.Holder> {
    private List<ProviderInfo.ProviderModelInfo> items = new ArrayList<>();

    public void setModels(List<ProviderInfo.ProviderModelInfo> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_model_simple, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ProviderInfo.ProviderModelInfo m = items.get(position);
        h.text.setText(m.nickname != null && !m.nickname.isEmpty() ? m.nickname : m.modelId);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView text;

        Holder(View v) {
            super(v);
            text = v.findViewById(R.id.modelId);
        }
    }
}
