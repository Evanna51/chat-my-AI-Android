package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ModelPickerAdapter extends RecyclerView.Adapter<ModelPickerAdapter.Holder> {
    private final List<ConfiguredModelPicker.Option> items;
    private final String selectedKey;
    private final OnSelectListener listener;

    public interface OnSelectListener {
        void onSelect(ConfiguredModelPicker.Option option);
    }

    public ModelPickerAdapter(List<ConfiguredModelPicker.Option> items, String selectedKey, OnSelectListener listener) {
        this.items = items;
        this.selectedKey = selectedKey;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_model_picker, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ConfiguredModelPicker.Option o = items.get(position);
        if (o == null) return;
        String display = o.displayName != null ? o.displayName : "";
        String sub = (o.modelId != null ? o.modelId : "") + " · " + (o.providerName != null ? o.providerName : "");
        h.modelDisplay.setText(display);
        h.modelSub.setText(sub);
        h.itemView.setSelected(selectedKey != null && selectedKey.equals(o.getStorageKey()));
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSelect(o);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView modelDisplay, modelSub;

        Holder(View v) {
            super(v);
            modelDisplay = v.findViewById(R.id.modelDisplay);
            modelSub = v.findViewById(R.id.modelSub);
        }
    }
}
