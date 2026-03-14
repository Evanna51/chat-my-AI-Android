package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SessionOutlineAdapter extends RecyclerView.Adapter<SessionOutlineAdapter.OutlineHolder> {
    public interface OnItemActionListener {
        void onEdit(SessionOutlineItem item);
        void onDelete(SessionOutlineItem item);
    }

    private final List<SessionOutlineItem> items = new ArrayList<>();
    private OnItemActionListener listener;

    public void setOnItemActionListener(OnItemActionListener listener) {
        this.listener = listener;
    }

    public void setItems(List<SessionOutlineItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public OutlineHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session_outline, parent, false);
        return new OutlineHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull OutlineHolder holder, int position) {
        if (position < 0 || position >= items.size()) return;
        SessionOutlineItem item = items.get(position);
        holder.textType.setText(displayType(item != null ? item.type : ""));
        holder.textTitle.setText(item != null ? safe(item.title) : "");
        holder.textContent.setText(item != null ? safe(item.content) : "");
        holder.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(item);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class OutlineHolder extends RecyclerView.ViewHolder {
        TextView textType;
        TextView textTitle;
        TextView textContent;
        View btnEdit;
        View btnDelete;

        OutlineHolder(View itemView) {
            super(itemView);
            textType = itemView.findViewById(R.id.textOutlineType);
            textTitle = itemView.findViewById(R.id.textOutlineTitle);
            textContent = itemView.findViewById(R.id.textOutlineContent);
            btnEdit = itemView.findViewById(R.id.btnOutlineEdit);
            btnDelete = itemView.findViewById(R.id.btnOutlineDelete);
        }
    }

    private String safe(String text) {
        return text != null ? text : "";
    }

    private String displayType(String type) {
        if ("material".equals(type)) return "资料";
        if ("task".equals(type)) return "人物资料";
        if ("world".equals(type)) return "世界背景";
        if ("knowledge".equals(type)) return "知情约束";
        return "章节";
    }
}
