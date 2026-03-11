package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MyAssistantListAdapter extends RecyclerView.Adapter<MyAssistantListAdapter.Holder> {
    private List<MyAssistant> items = new ArrayList<>();
    private OnAssistantClickListener listener;

    public interface OnAssistantClickListener {
        void onClick(MyAssistant assistant);
    }

    public void setOnAssistantClickListener(OnAssistantClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<MyAssistant> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_my_assistant, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        MyAssistant a = items.get(position);
        if (a == null) return;
        String name = a.name != null && !a.name.isEmpty() ? a.name : "未命名助手";
        holder.name.setText(name);
        AssistantAvatarHelper.bindAvatar(holder.avatarImage, holder.avatarText, a, name);
        holder.type.setText("writer".equals(a.type) ? "作家" : "默认");
        holder.promptPreview.setText(a.prompt != null && !a.prompt.isEmpty() ? a.prompt : "无设定");
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(a);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView avatarImage;
        TextView avatarText;
        TextView name;
        TextView type;
        TextView promptPreview;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatarImage = itemView.findViewById(R.id.imageAssistantAvatar);
            avatarText = itemView.findViewById(R.id.textAssistantAvatar);
            name = itemView.findViewById(R.id.textAssistantName);
            type = itemView.findViewById(R.id.textAssistantType);
            promptPreview = itemView.findViewById(R.id.textAssistantPromptPreview);
        }
    }
}
