package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AllConversationsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public interface ActionListener {
        void onOpen(SessionSummary session);
        void onToggleCategory(String category);
        void onSetCategory(SessionSummary session);
        void onGenerateOutline(SessionSummary session);
        void onTogglePin(SessionSummary session);
        void onToggleFavorite(SessionSummary session);
        void onDelete(SessionSummary session);
    }

    public static class Row {
        public boolean header;
        public String category;
        public int count;
        public boolean collapsed;
        public SessionSummary session;
    }

    private final List<Row> rows = new ArrayList<>();
    private ActionListener listener;

    public void setActionListener(ActionListener listener) {
        this.listener = listener;
    }

    public void setRows(List<Row> list) {
        rows.clear();
        if (list != null) rows.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (position < 0 || position >= rows.size()) return TYPE_ITEM;
        return rows.get(position).header ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_conversation_category_header, parent, false);
            return new HeaderHolder(view);
        }
        View view = inflater.inflate(R.layout.item_conversation_manage, parent, false);
        return new ItemHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position < 0 || position >= rows.size()) return;
        Row row = rows.get(position);
        if (holder instanceof HeaderHolder) {
            HeaderHolder h = (HeaderHolder) holder;
            String category = row.category != null ? row.category : "默认";
            h.title.setText(category + " (" + row.count + ")");
            h.expand.setRotation(row.collapsed ? 0f : 90f);
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onToggleCategory(category);
            });
            return;
        }
        ItemHolder h = (ItemHolder) holder;
        SessionSummary s = row.session;
        if (s == null) return;
        String title = s.title != null && !s.title.trim().isEmpty() ? s.title.trim() : "新对话";
        if (s.hidden) title = "🙈 " + title;
        if (s.pinned) title = "📌 " + title;
        else if (s.favorite) title = "★ " + title;
        h.title.setText(title);
        h.time.setText(s.lastAt > 0 ? SDF.format(new Date(s.lastAt)) : "");
        if (h.outline != null) {
            String outline = s.outline != null ? s.outline.trim() : "";
            if (outline.isEmpty()) {
                h.outline.setVisibility(View.GONE);
            } else {
                h.outline.setText(outline);
                h.outline.setVisibility(View.VISIBLE);
            }
        }
        int activeColor = MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorPrimary);
        int normalColor = ContextCompat.getColor(h.itemView.getContext(), R.color.list_icon_color);
        ImageViewCompat.setImageTintList(h.btnPin, android.content.res.ColorStateList.valueOf(s.pinned ? activeColor : normalColor));
        ImageViewCompat.setImageTintList(h.btnFavorite, android.content.res.ColorStateList.valueOf(s.favorite ? activeColor : normalColor));
        ImageViewCompat.setImageTintList(h.btnOutline, android.content.res.ColorStateList.valueOf(normalColor));
        ImageViewCompat.setImageTintList(h.btnCategory, android.content.res.ColorStateList.valueOf(normalColor));
        ImageViewCompat.setImageTintList(h.btnDelete, android.content.res.ColorStateList.valueOf(normalColor));
        h.btnOutline.setImageAlpha(200);
        h.btnCategory.setImageAlpha(200);
        h.btnDelete.setImageAlpha(200);
        h.btnPin.setImageAlpha(s.pinned ? 255 : 200);
        h.btnFavorite.setImageAlpha(s.favorite ? 255 : 200);
        h.btnOutline.setOnClickListener(v -> {
            if (listener != null) listener.onGenerateOutline(s);
        });
        h.btnCategory.setOnClickListener(v -> {
            if (listener != null) listener.onSetCategory(s);
        });
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onOpen(s);
        });
        h.btnPin.setOnClickListener(v -> {
            if (listener != null) listener.onTogglePin(s);
        });
        h.btnFavorite.setOnClickListener(v -> {
            if (listener != null) listener.onToggleFavorite(s);
        });
        h.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(s);
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        TextView title;
        ImageView expand;
        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.textCategoryTitle);
            expand = itemView.findViewById(R.id.iconCategoryExpand);
        }
    }

    static class ItemHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView time;
        TextView outline;
        ImageButton btnOutline;
        ImageButton btnCategory;
        ImageButton btnPin;
        ImageButton btnFavorite;
        ImageButton btnDelete;

        ItemHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.textConversationTitle);
            time = itemView.findViewById(R.id.textConversationTime);
            outline = itemView.findViewById(R.id.textConversationOutline);
            btnOutline = itemView.findViewById(R.id.btnOutline);
            btnCategory = itemView.findViewById(R.id.btnCategory);
            btnPin = itemView.findViewById(R.id.btnPin);
            btnFavorite = itemView.findViewById(R.id.btnFavorite);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
