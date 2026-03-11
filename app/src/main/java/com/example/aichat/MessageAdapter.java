package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import io.noties.markwon.Markwon;

import java.util.ArrayDeque;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int VIEW_USER = 0;
    private static final int VIEW_ASSISTANT = 1;
    private static final int DEFAULT_EXPANDED_RECENT_AI = 3;
    private static final int MAX_MARKDOWN_EXPANDED = 4;
    private static final long MARKDOWN_RENDER_THROTTLE_MS = 80L;

    private final List<Message> messages = new ArrayList<>();
    private int focusedPosition = -1;
    private final Set<Message> expandedReasoningMessages =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Message pinnedUserMessage;
    private Message pinnedAssistantMessage;
    private boolean hidePinnedAssistantActions;
    private OnMessageActionListener actionListener;
    private final SimpleDateFormat timestampFormat;
    private final AssistantMarkdownStateStore assistantStateStore;
    private OnAssistantStateChangedListener assistantStateChangedListener;
    private Markwon markwon;
    private final Map<Message, String> markdownRenderedSource = new IdentityHashMap<>();
    private final Map<Message, Long> markdownLastRenderAt = new IdentityHashMap<>();

    public MessageAdapter() {
        this(new AssistantMarkdownStateStore());
    }

    public MessageAdapter(AssistantMarkdownStateStore stateStore) {
        this.assistantStateStore = stateStore != null ? stateStore : new AssistantMarkdownStateStore();
        timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        timestampFormat.setTimeZone(TimeZone.getDefault());
    }

    public interface OnMessageActionListener {
        void onRegenerate(Message message);
        void onEdit(Message message);
        void onCopy(Message message);
        void onDelete(Message message);
    }

    public interface OnAssistantStateChangedListener {
        void onAssistantStateChanged();
    }

    public static class AssistantMarkdownStateStore {
        private final Set<Message> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Message> seenAssistants = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Deque<Message> expandStack = new ArrayDeque<>();
        private final Set<Message> activeMessages = Collections.newSetFromMap(new IdentityHashMap<>());

        public void onAllMessagesChanged(List<Message> allMessages) {
            activeMessages.clear();
            if (allMessages != null) activeMessages.addAll(allMessages);
            expanded.retainAll(activeMessages);
            seenAssistants.retainAll(activeMessages);
            rebuildStack();

            Set<Message> recent = collectRecentAssistants(allMessages, DEFAULT_EXPANDED_RECENT_AI);
            if (allMessages == null) return;
            for (Message m : allMessages) {
                if (m == null || m.role != Message.ROLE_ASSISTANT) continue;
                if (seenAssistants.contains(m)) continue;
                seenAssistants.add(m);
                if (recent.contains(m)) {
                    expanded.add(m);
                    pushFront(m);
                }
            }
            trimExpandedToLimit();
        }

        public boolean isExpanded(Message m) {
            return m != null && expanded.contains(m);
        }

        public List<Message> toggle(Message m) {
            List<Message> changed = new ArrayList<>();
            if (m == null || m.role != Message.ROLE_ASSISTANT) return changed;
            if (expanded.contains(m)) {
                expanded.remove(m);
                removeFromStack(m);
                changed.add(m);
                return changed;
            }
            expanded.add(m);
            pushFront(m);
            changed.add(m);
            while (expandStack.size() > MAX_MARKDOWN_EXPANDED) {
                Message removed = expandStack.removeLast();
                if (removed == null) continue;
                expanded.remove(removed);
                changed.add(removed);
            }
            return changed;
        }

        private void trimExpandedToLimit() {
            while (expandStack.size() > MAX_MARKDOWN_EXPANDED) {
                Message removed = expandStack.removeLast();
                if (removed != null) expanded.remove(removed);
            }
        }

        private void rebuildStack() {
            expandStack.removeIf(item -> item == null || !expanded.contains(item));
        }

        private void pushFront(Message m) {
            removeFromStack(m);
            expandStack.addFirst(m);
        }

        private void removeFromStack(Message m) {
            expandStack.removeIf(item -> item == m);
        }

        private Set<Message> collectRecentAssistants(List<Message> allMessages, int count) {
            Set<Message> recent = Collections.newSetFromMap(new IdentityHashMap<>());
            if (allMessages == null || count <= 0) return recent;
            int matched = 0;
            for (int i = allMessages.size() - 1; i >= 0 && matched < count; i--) {
                Message m = allMessages.get(i);
                if (m == null || m.role != Message.ROLE_ASSISTANT) continue;
                recent.add(m);
                matched++;
            }
            return recent;
        }
    }

    public void setOnMessageActionListener(OnMessageActionListener listener) {
        this.actionListener = listener;
    }

    public void setOnAssistantStateChangedListener(OnAssistantStateChangedListener listener) {
        this.assistantStateChangedListener = listener;
    }

    public void setMessages(List<Message> list) {
        messages.clear();
        if (list != null) {
            messages.addAll(list);
        }
        expandedReasoningMessages.retainAll(messages);
        markdownRenderedSource.keySet().retainAll(messages);
        markdownLastRenderAt.keySet().retainAll(messages);
        focusedPosition = messages.isEmpty() ? -1 : messages.size() - 1;
        notifyDataSetChanged();
    }

    public void addMessage(Message msg) {
        messages.add(msg);
        notifyItemInserted(messages.size() - 1);
    }

    public boolean notifyMessageChanged(Message target) {
        if (target == null) return false;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) {
                notifyItemChanged(i);
                return true;
            }
        }
        return false;
    }

    public void setPinnedActionMessages(Message userMessage, Message assistantMessage, boolean hideAssistantActions) {
        this.pinnedUserMessage = userMessage;
        this.pinnedAssistantMessage = assistantMessage;
        this.hidePinnedAssistantActions = hideAssistantActions;
    }

    public List<Message> getMessages() {
        return new ArrayList<>(messages);
    }

    public void clearFocus() {
        if (focusedPosition < 0) return;
        int prev = focusedPosition;
        focusedPosition = -1;
        notifyItemChanged(prev);
    }

    @Override
    public int getItemViewType(int position) {
        if (position < 0 || position >= messages.size()) return VIEW_USER;
        Message m = messages.get(position);
        return (m != null && m.role == Message.ROLE_USER) ? VIEW_USER : VIEW_ASSISTANT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (markwon == null) {
            markwon = Markwon.create(parent.getContext().getApplicationContext());
        }
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_USER) {
            View v = inflater.inflate(R.layout.item_message_user, parent, false);
            return new UserHolder(v);
        } else {
            View v = inflater.inflate(R.layout.item_message_assistant, parent, false);
            return new AssistantHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position < 0 || position >= messages.size()) return;
        Message m = messages.get(position);
        String content = (m != null && m.content != null) ? m.content : "";
        boolean focused = position == focusedPosition;
        boolean pinnedUser = m != null && m == pinnedUserMessage;
        boolean pinnedAssistant = m != null && m == pinnedAssistantMessage;
        boolean showActions = focused || pinnedUser || (pinnedAssistant && !hidePinnedAssistantActions);
        if (holder instanceof UserHolder) {
            UserHolder h = (UserHolder) holder;
            h.textTimestamp.setText(formatTimestamp(m != null ? m.createdAt : 0));
            h.textContent.setText(content);
            h.layoutActions.setVisibility(showActions ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> focus(position));
            h.actionRegenerate.setOnClickListener(v -> { if (actionListener != null) actionListener.onRegenerate(m); });
            h.actionEdit.setOnClickListener(v -> { if (actionListener != null) actionListener.onEdit(m); });
            h.actionCopy.setOnClickListener(v -> { if (actionListener != null) actionListener.onCopy(m); });
            h.actionDelete.setOnClickListener(v -> { if (actionListener != null) actionListener.onDelete(m); });
        } else if (holder instanceof AssistantHolder) {
            AssistantHolder h = (AssistantHolder) holder;
            h.textTimestamp.setText(formatTimestamp(m != null ? m.createdAt : 0));
            boolean expanded = m != null && assistantStateStore.isExpanded(m);
            boolean hasVisibleContent = !content.trim().isEmpty();
            h.textCollapseToggle.setVisibility(hasVisibleContent ? View.VISIBLE : View.GONE);
            h.textCollapseToggle.setText(expanded ? "▲ 收起" : "▼ 展开");
            h.textContent.setVisibility(hasVisibleContent ? View.VISIBLE : View.GONE);
            if (hasVisibleContent) bindAssistantContent(h, m, content, expanded);
            h.layoutActions.setVisibility((showActions || hasVisibleContent) ? View.VISIBLE : View.GONE);
            h.actionEdit.setVisibility(showActions ? View.VISIBLE : View.GONE);
            h.actionCopy.setVisibility(showActions ? View.VISIBLE : View.GONE);
            h.actionDelete.setVisibility(showActions ? View.VISIBLE : View.GONE);
            bindReasoning(h, m, position);
            h.itemView.setOnClickListener(v -> focus(position));
            h.actionEdit.setOnClickListener(v -> { if (actionListener != null) actionListener.onEdit(m); });
            h.actionCopy.setOnClickListener(v -> { if (actionListener != null) actionListener.onCopy(m); });
            h.actionDelete.setOnClickListener(v -> { if (actionListener != null) actionListener.onDelete(m); });
            h.textCollapseToggle.setOnClickListener(v -> toggleAssistantExpanded(h, m));
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class UserHolder extends RecyclerView.ViewHolder {
        TextView textTimestamp;
        TextView textContent;
        View layoutActions;
        View actionRegenerate;
        View actionEdit;
        View actionCopy;
        View actionDelete;

        UserHolder(View itemView) {
            super(itemView);
            textTimestamp = itemView.findViewById(R.id.textTimestamp);
            textContent = itemView.findViewById(R.id.textContent);
            layoutActions = itemView.findViewById(R.id.layoutActions);
            actionRegenerate = itemView.findViewById(R.id.actionRegenerate);
            actionEdit = itemView.findViewById(R.id.actionEdit);
            actionCopy = itemView.findViewById(R.id.actionCopy);
            actionDelete = itemView.findViewById(R.id.actionDelete);
        }
    }

    static class AssistantHolder extends RecyclerView.ViewHolder {
        TextView textTimestamp;
        TextView textContent;
        TextView textCollapseToggle;
        View layoutActions;
        View layoutReasoning;
        TextView textReasoningHeader;
        TextView textReasoningContent;
        TextView textUsage;
        View actionEdit;
        View actionCopy;
        View actionDelete;

        AssistantHolder(View itemView) {
            super(itemView);
            textTimestamp = itemView.findViewById(R.id.textTimestamp);
            textContent = itemView.findViewById(R.id.textContent);
            textCollapseToggle = itemView.findViewById(R.id.textCollapseToggle);
            layoutActions = itemView.findViewById(R.id.layoutActions);
            layoutReasoning = itemView.findViewById(R.id.layoutReasoning);
            textReasoningHeader = itemView.findViewById(R.id.textReasoningHeader);
            textReasoningContent = itemView.findViewById(R.id.textReasoningContent);
            textUsage = itemView.findViewById(R.id.textUsage);
            actionEdit = itemView.findViewById(R.id.actionEdit);
            actionCopy = itemView.findViewById(R.id.actionCopy);
            actionDelete = itemView.findViewById(R.id.actionDelete);
        }
    }

    private void focus(int position) {
        if (position < 0 || position >= messages.size()) return;
        if (focusedPosition == position) return;
        int prev = focusedPosition;
        focusedPosition = position;
        if (prev >= 0) notifyItemChanged(prev);
        notifyItemChanged(position);
    }

    private void bindReasoning(AssistantHolder h, Message m, int position) {
        boolean hasReasoning = m != null && m.reasoning != null && !m.reasoning.trim().isEmpty();
        boolean hasThinkingState = m != null && (m.thinkingRunning || m.thinkingElapsedMs > 0 || hasReasoning);
        boolean hasUsage = m != null && (m.totalTokens > 0 || m.elapsedMs > 0);
        h.layoutReasoning.setVisibility(hasThinkingState ? View.VISIBLE : View.GONE);
        if (hasThinkingState) {
            h.textReasoningHeader.setVisibility(View.VISIBLE);
            boolean expanded = m != null && expandedReasoningMessages.contains(m);
            h.textReasoningContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
            String thinkingTime = formatSeconds(m != null ? m.thinkingElapsedMs : 0);
            h.textReasoningHeader.setText((expanded ? "Thinking ▲ " : "Thinking ▼ ") + thinkingTime);
            String reasoning = m != null ? m.reasoning : null;
            h.textReasoningContent.setText((reasoning == null || reasoning.trim().isEmpty()) ? "Thinking 中..." : reasoning);
            h.textReasoningHeader.setOnClickListener(v -> {
                if (m == null) return;
                if (expandedReasoningMessages.contains(m)) {
                    expandedReasoningMessages.remove(m);
                } else {
                    expandedReasoningMessages.add(m);
                }
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) {
                    notifyItemChanged(p);
                }
            });
        } else {
            h.textReasoningHeader.setVisibility(View.GONE);
            h.textReasoningContent.setVisibility(View.GONE);
            h.textReasoningHeader.setOnClickListener(null);
            if (m != null) {
                expandedReasoningMessages.remove(m);
            }
        }
        if (hasUsage) {
            String usage = "tokens: " + m.totalTokens
                    + "（in " + Math.max(m.promptTokens, 0)
                    + " / out " + Math.max(m.completionTokens, 0) + "）"
                    + "  耗时: " + formatSeconds(m.elapsedMs);
            h.textUsage.setText(usage);
            h.textUsage.setVisibility(View.VISIBLE);
        } else {
            h.textUsage.setVisibility(View.GONE);
        }
    }

    private void toggleAssistantExpanded(AssistantHolder h, Message m) {
        if (m == null || m.role != Message.ROLE_ASSISTANT) return;
        List<Message> changed = assistantStateStore.toggle(m);
        if (changed.isEmpty()) return;
        for (Message one : changed) {
            markdownRenderedSource.remove(one);
            markdownLastRenderAt.remove(one);
        }
        int p = h.getBindingAdapterPosition();
        if (p != RecyclerView.NO_POSITION) notifyItemChanged(p);
        if (assistantStateChangedListener != null) {
            assistantStateChangedListener.onAssistantStateChanged();
        }
    }

    private void bindAssistantContent(AssistantHolder h, Message m, String content, boolean expanded) {
        if (!expanded) {
            h.textContent.setText(content);
            h.textContent.setMaxLines(3);
            h.textContent.setEllipsize(TextUtils.TruncateAt.END);
            return;
        }
        h.textContent.setMaxLines(Integer.MAX_VALUE);
        h.textContent.setEllipsize(null);
        if (markwon == null || m == null) {
            h.textContent.setText(content);
            return;
        }
        String lastSource = markdownRenderedSource.get(m);
        long lastAt = markdownLastRenderAt.containsKey(m) ? markdownLastRenderAt.get(m) : 0L;
        long now = System.currentTimeMillis();
        boolean contentChanged = lastSource == null || !content.equals(lastSource);
        boolean canRenderMarkdownNow = !contentChanged || (now - lastAt >= MARKDOWN_RENDER_THROTTLE_MS);
        if (!canRenderMarkdownNow) {
            h.textContent.setText(content);
            return;
        }
        markwon.setMarkdown(h.textContent, content);
        markdownRenderedSource.put(m, content);
        markdownLastRenderAt.put(m, now);
    }

    private String formatSeconds(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", Math.max(ms, 0) / 1000.0f);
    }

    private String formatTimestamp(long createdAt) {
        if (createdAt <= 0) return "";
        return timestampFormat.format(createdAt);
    }
}
