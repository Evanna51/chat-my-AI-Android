package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SessionListAdapter extends RecyclerView.Adapter<SessionListAdapter.Holder> {
    private List<SessionSummary> items = new ArrayList<>();
    private OnSessionClickListener listener;
    private SessionActionListener actionListener;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public interface OnSessionClickListener {
        void onSessionClick(SessionSummary s);
    }

    public interface SessionActionListener {
        void onHide(SessionSummary s);
        void onDelete(SessionSummary s);
    }

    public void setOnSessionClickListener(OnSessionClickListener l) { listener = l; }
    public void setSessionActionListener(SessionActionListener l) { actionListener = l; }

    public void setSessions(List<SessionSummary> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        if (position < 0 || position >= items.size()) return;
        SessionSummary s = items.get(position);
        if (s == null) return;
        if (h.avatar != null) {
            String avatar = s.avatar != null && !s.avatar.trim().isEmpty() ? s.avatar.trim() : "🤖";
            h.avatar.setText(avatar);
        }
        if (h.title != null) {
            String title = s.title != null && !s.title.isEmpty() ? s.title : "新对话";
            if (s.pinned) title = "📌 " + title;
            else if (s.favorite) title = "★ " + title;
            h.title.setText(title);
        }
        if (h.time != null) h.time.setText(s.lastAt > 0 ? SDF.format(new Date(s.lastAt)) : "");
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onSessionClick(s); });
        if (h.hide != null) {
            h.hide.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onHide(s);
            });
        }
        if (h.delete != null) {
            h.delete.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onDelete(s);
            });
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        TextView avatar, title, time;
        ImageButton hide, delete;
        Holder(View v) {
            super(v);
            avatar = v.findViewById(R.id.sessionAvatar);
            title = v.findViewById(R.id.sessionTitle);
            time = v.findViewById(R.id.sessionTime);
            hide = v.findViewById(R.id.btnHideSession);
            delete = v.findViewById(R.id.btnDeleteSession);
        }
    }
}
