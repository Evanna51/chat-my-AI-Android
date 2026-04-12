package com.example.aichat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.TextUtils;
import android.widget.ImageView;
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
    private static final int MAX_EXPANDED_ASSISTANT_ACTIONS = 3;
    private static final int DEFAULT_EXPANDED_RECENT_AI = 3;
    private static final int MAX_MARKDOWN_EXPANDED = 4;
    private static final long MARKDOWN_RENDER_THROTTLE_MS = 80L;
    private static final Object PAYLOAD_STREAM_TICK = new Object();
    private static final String CHARACTER_MEMORY_LOADING_TEXT = "[...正在输入中]";

    private final List<Message> messages = new ArrayList<>();
    private final Set<Message> expandedReasoningMessages =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Message pinnedUserMessage;
    private Message pinnedAssistantMessage;
    private boolean hidePinnedAssistantActions;
    private OnMessageActionListener actionListener;
    private final SimpleDateFormat timestampFormat;
    private final AssistantMarkdownStateStore assistantStateStore;
    private final ActionPanelStateStore actionPanelStateStore;
    private OnAssistantStateChangedListener assistantStateChangedListener;
    private Markwon markwon;
    private final Map<Message, String> markdownRenderedSource = new IdentityHashMap<>();
    private final Map<Message, Long> markdownLastRenderAt = new IdentityHashMap<>();
    private final Set<AssistantHolder> attachedAssistantHolders =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean writerMode;
    private boolean disableAssistantCollapseToggle;
    private boolean autoFocusLatestOnSetMessages = true;
    private int affixViewportTop = Integer.MIN_VALUE;
    private int affixViewportBottom = Integer.MIN_VALUE;

    public MessageAdapter() {
        this(new AssistantMarkdownStateStore(), new ActionPanelStateStore());
    }

    public MessageAdapter(AssistantMarkdownStateStore stateStore) {
        this(stateStore, new ActionPanelStateStore());
    }

    public MessageAdapter(AssistantMarkdownStateStore stateStore, ActionPanelStateStore actionStore) {
        this.assistantStateStore = stateStore != null ? stateStore : new AssistantMarkdownStateStore();
        this.actionPanelStateStore = actionStore != null ? actionStore : new ActionPanelStateStore();
        timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        timestampFormat.setTimeZone(TimeZone.getDefault());
    }

    public interface OnMessageActionListener {
        void onRegenerate(Message message);
        void onEdit(Message message);
        void onCopy(Message message);
        void onOpen(Message message);
        void onOutline(Message message);
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

    public static class ActionPanelStateStore {
        private final Set<Message> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Deque<Message> assistantExpandStack = new ArrayDeque<>();
        private final Set<Message> activeMessages = Collections.newSetFromMap(new IdentityHashMap<>());

        public void onAllMessagesChanged(List<Message> allMessages) {
            activeMessages.clear();
            if (allMessages != null) activeMessages.addAll(allMessages);
            expanded.retainAll(activeMessages);
            assistantExpandStack.removeIf(item -> item == null || !expanded.contains(item));
        }

        public boolean isExpanded(Message message) {
            return message != null && expanded.contains(message);
        }

        public List<Message> expand(Message message) {
            List<Message> changed = new ArrayList<>();
            if (message == null) return changed;
            if (expanded.contains(message)) return changed;
            expanded.add(message);
            changed.add(message);
            // Only assistant messages participate in "max 3 expanded" eviction.
            if (message.role == Message.ROLE_ASSISTANT) {
                removeFromAssistantStack(message);
                assistantExpandStack.addFirst(message);
                while (assistantExpandStack.size() > MAX_EXPANDED_ASSISTANT_ACTIONS) {
                    Message removed = assistantExpandStack.removeLast();
                    if (removed == null) continue;
                    expanded.remove(removed);
                    changed.add(removed);
                }
            }
            return changed;
        }

        private void removeFromAssistantStack(Message message) {
            assistantExpandStack.removeIf(item -> item == message);
        }
    }

    public void setOnMessageActionListener(OnMessageActionListener listener) {
        this.actionListener = listener;
    }

    public void setOnAssistantStateChangedListener(OnAssistantStateChangedListener listener) {
        this.assistantStateChangedListener = listener;
    }

    public void setWriterMode(boolean enabled) {
        writerMode = enabled;
        notifyDataSetChanged();
    }

    public void setDisableAssistantCollapseToggle(boolean disabled) {
        disableAssistantCollapseToggle = disabled;
        notifyDataSetChanged();
    }

    public void setAutoFocusLatestOnSetMessages(boolean enabled) {
        autoFocusLatestOnSetMessages = enabled;
    }

    public void setCollapseToggleAffixViewport(int viewportTop, int viewportBottom) {
        affixViewportTop = viewportTop;
        affixViewportBottom = viewportBottom;
        updateCollapseToggleAffixForAttachedHolders();
    }

    public void setMessages(List<Message> list) {
        messages.clear();
        if (list != null) {
            messages.addAll(list);
        }
        expandedReasoningMessages.retainAll(messages);
        actionPanelStateStore.onAllMessagesChanged(messages);
        markdownRenderedSource.keySet().retainAll(messages);
        markdownLastRenderAt.keySet().retainAll(messages);
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
                notifyItemChanged(i, PAYLOAD_STREAM_TICK);
                return true;
            }
        }
        return false;
    }

    public boolean renderStreamingMessageIfVisible(Message target) {
        if (target == null) return false;
        boolean rendered = false;
        List<AssistantHolder> snapshot = new ArrayList<>(attachedAssistantHolders);
        for (AssistantHolder h : snapshot) {
            if (h == null || h.boundMessage != target) continue;
            String content = target.content != null ? target.content : "";
            boolean hasVisibleContent = !content.trim().isEmpty();
            h.textContent.setVisibility(hasVisibleContent ? View.VISIBLE : View.GONE);
            if (hasVisibleContent) {
                bindAssistantContentStreaming(h, target, content);
            }
            if (h.lastHasVisibleContent != hasVisibleContent) {
                h.textCollapseToggle.setVisibility(hasVisibleContent ? View.VISIBLE : View.GONE);
                h.lastHasVisibleContent = hasVisibleContent;
            }
            bindReasoning(h, target, h.getBindingAdapterPosition());
            applyCollapseToggleAffix(h);
            rendered = true;
        }
        return rendered;
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
        // Action panel visibility is controlled by explicit expand button.
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
        bindViewHolder(holder, position, true);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            bindViewHolder(holder, position, true);
            return;
        }
        if (hasStreamTickPayload(payloads) && holder instanceof AssistantHolder) {
            bindViewHolder(holder, position, false);
            return;
        }
        bindViewHolder(holder, position, true);
    }

    private void bindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean fullBind) {
        if (position < 0 || position >= messages.size()) return;
        Message m = messages.get(position);
        String content = (m != null && m.content != null) ? m.content : "";
        boolean expandedByUser = m != null && actionPanelStateStore.isExpanded(m);
        boolean pinnedUser = m != null && m == pinnedUserMessage;
        boolean pinnedAssistant = m != null && m == pinnedAssistantMessage;
        boolean showActions = expandedByUser || pinnedUser || (pinnedAssistant && !hidePinnedAssistantActions);
        if (holder instanceof UserHolder) {
            UserHolder h = (UserHolder) holder;
            h.textTimestamp.setText(formatTimestamp(m != null ? m.createdAt : 0));
            h.textContent.setText(content);
            h.layoutActions.setVisibility(showActions ? View.VISIBLE : View.GONE);
            h.actionExpand.setAlpha(showActions ? 0.55f : 1f);
            h.actionOutline.setVisibility(writerMode ? View.VISIBLE : View.GONE);
            h.actionExpand.setOnClickListener(v -> expandActionPanel(m));
            h.itemView.setOnClickListener(null);
            h.actionRegenerate.setOnClickListener(v -> { if (actionListener != null) actionListener.onRegenerate(m); });
            h.actionEdit.setOnClickListener(v -> { if (actionListener != null) actionListener.onEdit(m); });
            h.actionCopy.setOnClickListener(v -> { if (actionListener != null) actionListener.onCopy(m); });
            h.actionOpen.setOnClickListener(v -> { if (actionListener != null) actionListener.onOpen(m); });
            h.actionOutline.setOnClickListener(v -> { if (actionListener != null) actionListener.onOutline(m); });
            h.actionDelete.setOnClickListener(v -> { if (actionListener != null) actionListener.onDelete(m); });
        } else if (holder instanceof AssistantHolder) {
            AssistantHolder h = (AssistantHolder) holder;
            h.boundMessage = m;
            h.textTimestamp.setText(formatTimestamp(m != null ? m.createdAt : 0));
            h.textContent.setAlpha(1f);
            boolean isMemoryLoadingPlaceholder = m != null
                    && m.role == Message.ROLE_ASSISTANT
                    && CHARACTER_MEMORY_LOADING_TEXT.equals(content != null ? content.trim() : "");
            if (isMemoryLoadingPlaceholder) {
                h.textContent.setVisibility(View.VISIBLE);
                h.textContent.setText(CHARACTER_MEMORY_LOADING_TEXT);
                h.textContent.setMaxLines(1);
                h.textContent.setEllipsize(TextUtils.TruncateAt.END);
                h.textContent.setAlpha(0.72f);
                h.layoutReasoning.setVisibility(View.GONE);
                h.textUsage.setVisibility(View.GONE);
                h.actionExpand.setVisibility(View.GONE);
                h.layoutActions.setVisibility(View.GONE);
                h.textCollapseToggle.setVisibility(View.GONE);
                if (fullBind) h.itemView.setOnClickListener(null);
                return;
            }
            boolean expanded = m != null && assistantStateStore.isExpanded(m);
            if (disableAssistantCollapseToggle) expanded = true;
            boolean hasVisibleContent = !content.trim().isEmpty();
            h.layoutActions.setVisibility(showActions ? View.VISIBLE : View.GONE);
            h.actionExpand.setVisibility(View.VISIBLE);
            h.actionExpand.setAlpha(showActions ? 0.55f : 1f);
            if (fullBind || h.lastHasVisibleContent != hasVisibleContent) {
                if (disableAssistantCollapseToggle) {
                    h.textCollapseToggle.setVisibility(View.GONE);
                } else {
                    h.textCollapseToggle.setVisibility(hasVisibleContent ? View.VISIBLE : View.GONE);
                }
                h.lastHasVisibleContent = hasVisibleContent;
            }
            if (!disableAssistantCollapseToggle) {
                setCollapseToggleLabel(h.textCollapseToggle, expanded);
            }
            if (disableAssistantCollapseToggle) {
                // Character chats do not use assistant content collapse controls at all.
                h.textCollapseToggle.setOnClickListener(null);
            }
            h.textContent.setVisibility(hasVisibleContent ? View.VISIBLE : View.GONE);
            if (hasVisibleContent) bindAssistantContent(h, m, content, expanded);
            h.actionOutline.setVisibility(writerMode && showActions ? View.VISIBLE : View.INVISIBLE);
            h.actionEdit.setVisibility(showActions ? View.VISIBLE : View.INVISIBLE);
            h.actionCopy.setVisibility(showActions ? View.VISIBLE : View.INVISIBLE);
            h.actionOpen.setVisibility(showActions ? View.VISIBLE : View.INVISIBLE);
            h.actionDelete.setVisibility(showActions ? View.VISIBLE : View.INVISIBLE);
            bindReasoning(h, m, position);
            applyCollapseToggleAffix(h);
            if (fullBind) {
                h.itemView.setOnClickListener(null);
                h.actionExpand.setOnClickListener(v -> expandActionPanel(m));
                h.actionEdit.setOnClickListener(v -> { if (actionListener != null) actionListener.onEdit(m); });
                h.actionCopy.setOnClickListener(v -> { if (actionListener != null) actionListener.onCopy(m); });
                h.actionOpen.setOnClickListener(v -> { if (actionListener != null) actionListener.onOpen(m); });
                h.actionOutline.setOnClickListener(v -> { if (actionListener != null) actionListener.onOutline(m); });
                h.actionDelete.setOnClickListener(v -> { if (actionListener != null) actionListener.onDelete(m); });
                if (disableAssistantCollapseToggle) {
                    h.textCollapseToggle.setOnClickListener(null);
                } else {
                    h.textCollapseToggle.setOnClickListener(v -> toggleAssistantExpanded(h, m));
                }
            }
        }
    }

    private boolean hasStreamTickPayload(@NonNull List<Object> payloads) {
        for (Object payload : payloads) {
            if (payload == PAYLOAD_STREAM_TICK) return true;
        }
        return false;
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (holder instanceof AssistantHolder) {
            AssistantHolder h = (AssistantHolder) holder;
            attachedAssistantHolders.add(h);
            applyCollapseToggleAffix(h);
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof AssistantHolder) {
            attachedAssistantHolders.remove((AssistantHolder) holder);
        }
        super.onViewDetachedFromWindow(holder);
    }

    static class UserHolder extends RecyclerView.ViewHolder {
        TextView textTimestamp;
        TextView textContent;
        View layoutActions;
        View actionExpand;
        View actionRegenerate;
        View actionEdit;
        View actionCopy;
        View actionOpen;
        View actionOutline;
        View actionDelete;

        UserHolder(View itemView) {
            super(itemView);
            textTimestamp = itemView.findViewById(R.id.textTimestamp);
            textContent = itemView.findViewById(R.id.textContent);
            layoutActions = itemView.findViewById(R.id.layoutActions);
            actionExpand = itemView.findViewById(R.id.actionExpand);
            actionRegenerate = itemView.findViewById(R.id.actionRegenerate);
            actionEdit = itemView.findViewById(R.id.actionEdit);
            actionCopy = itemView.findViewById(R.id.actionCopy);
            actionOpen = itemView.findViewById(R.id.actionOpen);
            actionOutline = itemView.findViewById(R.id.actionOutline);
            actionDelete = itemView.findViewById(R.id.actionDelete);
        }
    }

    static class AssistantHolder extends RecyclerView.ViewHolder {
        TextView textTimestamp;
        TextView textContent;
        ImageView textCollapseToggle;
        View layoutAssistantBubble;
        View layoutActions;
        View actionExpand;
        View layoutReasoning;
        TextView textReasoningHeader;
        TextView textReasoningContent;
        TextView textUsage;
        View actionEdit;
        View actionCopy;
        View actionOpen;
        View actionOutline;
        View actionDelete;
        boolean lastHasVisibleContent;
        Message boundMessage;

        AssistantHolder(View itemView) {
            super(itemView);
            textTimestamp = itemView.findViewById(R.id.textTimestamp);
            textContent = itemView.findViewById(R.id.textContent);
            textCollapseToggle = itemView.findViewById(R.id.textCollapseToggle);
            layoutAssistantBubble = itemView.findViewById(R.id.layoutAssistantBubble);
            layoutActions = itemView.findViewById(R.id.layoutActions);
            actionExpand = itemView.findViewById(R.id.actionExpand);
            layoutReasoning = itemView.findViewById(R.id.layoutReasoning);
            textReasoningHeader = itemView.findViewById(R.id.textReasoningHeader);
            textReasoningContent = itemView.findViewById(R.id.textReasoningContent);
            textUsage = itemView.findViewById(R.id.textUsage);
            actionEdit = itemView.findViewById(R.id.actionEdit);
            actionCopy = itemView.findViewById(R.id.actionCopy);
            actionOpen = itemView.findViewById(R.id.actionOpen);
            actionOutline = itemView.findViewById(R.id.actionOutline);
            actionDelete = itemView.findViewById(R.id.actionDelete);
            lastHasVisibleContent = false;
            boundMessage = null;
        }
    }

    private void expandActionPanel(Message message) {
        if (message == null) return;
        List<Message> changed = actionPanelStateStore.expand(message);
        if (changed.isEmpty()) return;
        for (Message one : changed) {
            int idx = indexOfMessage(one);
            if (idx >= 0) notifyItemChanged(idx);
        }
    }

    private int indexOfMessage(Message target) {
        if (target == null) return -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) return i;
        }
        return -1;
    }

    private void bindReasoning(AssistantHolder h, Message m, int position) {
        boolean hasReasoning = m != null && m.reasoning != null && !m.reasoning.trim().isEmpty();
        boolean hasThinkingState = m != null && (m.thinkingRunning || m.thinkingElapsedMs > 0 || hasReasoning);
        boolean hasUsage = m != null && (m.totalTokens > 0 || m.elapsedMs > 0);
        h.layoutReasoning.setVisibility(hasThinkingState ? View.VISIBLE : View.GONE);
        if (hasThinkingState) {
            h.textReasoningHeader.setVisibility(View.VISIBLE);
            boolean expanded = m != null && expandedReasoningMessages.contains(m);
            String thinkingTime = formatSeconds(m != null ? m.thinkingElapsedMs : 0);
            h.textReasoningHeader.setText((expanded ? "Thinking ▲ " : "Thinking ▼ ") + thinkingTime);
            String reasoning = m != null ? m.reasoning : null;
            String display = (reasoning == null || reasoning.trim().isEmpty()) ? "Thinking 中..." : reasoning;
            h.textReasoningContent.setVisibility(View.VISIBLE);
            h.textReasoningContent.setText(display);
            if (expanded) {
                h.textReasoningContent.setMaxLines(Integer.MAX_VALUE);
                h.textReasoningContent.setEllipsize(null);
            } else {
                // Collapsed preview: render only the first visible line.
                h.textReasoningContent.setMaxLines(1);
                h.textReasoningContent.setEllipsize(TextUtils.TruncateAt.END);
            }
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
            h.textContent.setMaxLines(3);
            h.textContent.setEllipsize(TextUtils.TruncateAt.END);
        } else {
            h.textContent.setMaxLines(Integer.MAX_VALUE);
            h.textContent.setEllipsize(null);
        }
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
            // Keep last rendered markdown to avoid flashing between plain text and markdown spans.
            if (lastSource == null) {
                h.textContent.setText(content);
            }
            return;
        }
        markwon.setMarkdown(h.textContent, content);
        markdownRenderedSource.put(m, content);
        markdownLastRenderAt.put(m, now);
    }

    private void bindAssistantContentStreaming(AssistantHolder h, Message m, String content) {
        boolean expanded = m != null && assistantStateStore.isExpanded(m);
        if (disableAssistantCollapseToggle) expanded = true;
        if (!expanded) {
            h.textContent.setText(content);
            h.textContent.setMaxLines(3);
            h.textContent.setEllipsize(TextUtils.TruncateAt.END);
            return;
        }
        h.textContent.setMaxLines(Integer.MAX_VALUE);
        h.textContent.setEllipsize(null);
        h.textContent.setText(content);
    }

    private void updateCollapseToggleAffixForAttachedHolders() {
        List<AssistantHolder> snapshot = new ArrayList<>(attachedAssistantHolders);
        for (AssistantHolder h : snapshot) {
            applyCollapseToggleAffix(h);
        }
    }

    private void applyCollapseToggleAffix(AssistantHolder h) {
        // Toggle is now in the action bar row; no affix positioning needed.
    }

    private void setCollapseToggleLabel(ImageView toggle, boolean expanded) {
        if (toggle == null) return;
        toggle.setImageResource(expanded ? R.drawable.ic_collapse_expand_less : R.drawable.ic_collapse_expand_more);
    }

    private String formatSeconds(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", Math.max(ms, 0) / 1000.0f);
    }

    private String formatTimestamp(long createdAt) {
        if (createdAt <= 0) return "";
        return timestampFormat.format(createdAt);
    }
}
