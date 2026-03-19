package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProviderCatalogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final List<Object> items = new ArrayList<>();
    private final Set<String> enabledIds = new HashSet<>();
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(ProviderCatalog.CatalogItem item);
    }

    public void setOnItemClickListener(OnItemClickListener l) { listener = l; }

    public void setData(List<ProviderCatalog.CatalogItem> catalog, Set<String> enabled) {
        items.clear();
        enabledIds.clear();
        if (enabled != null) enabledIds.addAll(enabled);
        String lastCat = null;
        for (ProviderCatalog.CatalogItem item : catalog) {
            if (!item.category.equals(lastCat)) {
                lastCat = item.category;
                items.add(lastCat);
            }
            items.add(item);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_provider_catalog_header, parent, false);
            return new HeaderHolder(v);
        } else {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_provider_catalog, parent, false);
            return new ItemHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        Object o = items.get(position);
        if (h instanceof HeaderHolder) {
            ((HeaderHolder) h).title.setText((String) o);
        } else {
            ProviderCatalog.CatalogItem item = (ProviderCatalog.CatalogItem) o;
            ItemHolder ih = (ItemHolder) h;
            ih.name.setText(item.name);
            ih.added.setVisibility(enabledIds.contains(item.id) ? View.VISIBLE : View.GONE);
            ih.itemView.setOnClickListener(v -> { if (listener != null) listener.onItemClick(item); });
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        TextView title;
        HeaderHolder(View v) {
            super(v);
            title = v.findViewById(R.id.headerTitle);
        }
    }

    static class ItemHolder extends RecyclerView.ViewHolder {
        TextView name, added;
        ItemHolder(View v) {
            super(v);
            name = v.findViewById(R.id.providerName);
            added = v.findViewById(R.id.labelAdded);
        }
    }
}
