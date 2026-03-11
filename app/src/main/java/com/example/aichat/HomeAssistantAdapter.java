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

public class HomeAssistantAdapter extends RecyclerView.Adapter<HomeAssistantAdapter.Holder> {
    private List<MyAssistant> items = new ArrayList<>();
    private OnAssistantClickListener listener;

    public interface OnAssistantClickListener {
        void onAssistantClick(MyAssistant assistant);
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
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_home_assistant, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        MyAssistant a = items.get(position);
        if (a == null) return;
        String n = a.name != null && !a.name.isEmpty() ? a.name : "助手";
        holder.name.setText(n);
        AssistantAvatarHelper.bindAvatar(holder.avatarImage, holder.avatar, a, n);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onAssistantClick(a);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView avatarImage;
        TextView avatar;
        TextView name;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatarImage = itemView.findViewById(R.id.imageAvatar);
            avatar = itemView.findViewById(R.id.textAvatar);
            name = itemView.findViewById(R.id.textName);
        }
    }
}
