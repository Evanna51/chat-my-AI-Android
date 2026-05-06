package com.example.aichat

import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.Deque
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone

class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder> {

    companion object {
        private const val VIEW_USER = 0
        private const val VIEW_ASSISTANT = 1
        private const val MAX_EXPANDED_ASSISTANT_ACTIONS = 3
        private const val DEFAULT_EXPANDED_RECENT_AI = 3
        private const val MAX_MARKDOWN_EXPANDED = 4
        private const val MARKDOWN_RENDER_THROTTLE_MS = 80L
        private val PAYLOAD_STREAM_TICK = Any()
        private const val CHARACTER_MEMORY_LOADING_TEXT = "[...正在输入中]"
    }

    private val messages: MutableList<Message> = ArrayList()
    private val expandedReasoningMessages: MutableSet<Message> =
        Collections.newSetFromMap(IdentityHashMap())
    private var pinnedUserMessage: Message? = null
    private var pinnedAssistantMessage: Message? = null
    private var hidePinnedAssistantActions: Boolean = false
    private var streamingAssistantMessage: Message? = null
    private var actionListener: OnMessageActionListener? = null
    private val timestampFormat: SimpleDateFormat
    private val timestampTodayFormat: SimpleDateFormat
    private val assistantStateStore: AssistantMarkdownStateStore
    private val actionPanelStateStore: ActionPanelStateStore
    private var assistantStateChangedListener: OnAssistantStateChangedListener? = null
    private var markwon: Markwon? = null
    private val markdownRenderedSource: MutableMap<Message, String> = IdentityHashMap()
    private val markdownLastRenderAt: MutableMap<Message, Long> = IdentityHashMap()
    private val attachedAssistantHolders: MutableSet<AssistantHolder> =
        Collections.newSetFromMap(IdentityHashMap())
    private var writerMode: Boolean = false
    private var characterMode: Boolean = false
    private var disableAssistantCollapseToggle: Boolean = false
    private var autoFocusLatestOnSetMessages: Boolean = true
    private var affixViewportTop: Int = Int.MIN_VALUE
    private var affixViewportBottom: Int = Int.MIN_VALUE
    private var userActionPopup: com.example.aichat.widget.MessageActionPopup? = null

    constructor() : this(AssistantMarkdownStateStore(), ActionPanelStateStore())

    constructor(stateStore: AssistantMarkdownStateStore) : this(stateStore, ActionPanelStateStore())

    constructor(stateStore: AssistantMarkdownStateStore?, actionStore: ActionPanelStateStore?) {
        this.assistantStateStore = stateStore ?: AssistantMarkdownStateStore()
        this.actionPanelStateStore = actionStore ?: ActionPanelStateStore()
        timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        timestampFormat.timeZone = TimeZone.getDefault()
        timestampTodayFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        timestampTodayFormat.timeZone = TimeZone.getDefault()
    }

    interface OnMessageActionListener {
        fun onRegenerate(message: Message)
        fun onEdit(message: Message)
        fun onCopy(message: Message)
        fun onOpen(message: Message)
        fun onOutline(message: Message)
        fun onDelete(message: Message)
        fun onVoicePlay(message: Message)
    }

    interface OnAssistantStateChangedListener {
        fun onAssistantStateChanged()
    }

    class AssistantMarkdownStateStore {
        private val expanded: MutableSet<Message> = Collections.newSetFromMap(IdentityHashMap())
        private val seenAssistants: MutableSet<Message> = Collections.newSetFromMap(IdentityHashMap())
        private val expandStack: Deque<Message> = ArrayDeque()
        private val activeMessages: MutableSet<Message> = Collections.newSetFromMap(IdentityHashMap())

        fun onAllMessagesChanged(allMessages: List<Message>?) {
            activeMessages.clear()
            if (allMessages != null) activeMessages.addAll(allMessages)
            expanded.retainAll(activeMessages)
            seenAssistants.retainAll(activeMessages)
            rebuildStack()

            val recent = collectRecentAssistants(allMessages, DEFAULT_EXPANDED_RECENT_AI)
            if (allMessages == null) return
            for (m in allMessages) {
                if (m.role != Message.ROLE_ASSISTANT) continue
                if (seenAssistants.contains(m)) continue
                seenAssistants.add(m)
                if (recent.contains(m)) {
                    expanded.add(m)
                    pushFront(m)
                }
            }
            trimExpandedToLimit()
        }

        fun isExpanded(m: Message?): Boolean {
            return m != null && expanded.contains(m)
        }

        fun toggle(m: Message?): List<Message> {
            val changed: MutableList<Message> = ArrayList()
            if (m == null || m.role != Message.ROLE_ASSISTANT) return changed
            if (expanded.contains(m)) {
                expanded.remove(m)
                removeFromStack(m)
                changed.add(m)
                return changed
            }
            expanded.add(m)
            pushFront(m)
            changed.add(m)
            while (expandStack.size > MAX_MARKDOWN_EXPANDED) {
                val removed = expandStack.removeLast() ?: continue
                expanded.remove(removed)
                changed.add(removed)
            }
            return changed
        }

        private fun trimExpandedToLimit() {
            while (expandStack.size > MAX_MARKDOWN_EXPANDED) {
                val removed = expandStack.removeLast()
                if (removed != null) expanded.remove(removed)
            }
        }

        private fun rebuildStack() {
            expandStack.removeIf { item -> item == null || !expanded.contains(item) }
        }

        private fun pushFront(m: Message) {
            removeFromStack(m)
            expandStack.addFirst(m)
        }

        private fun removeFromStack(m: Message) {
            expandStack.removeIf { item -> item === m }
        }

        private fun collectRecentAssistants(allMessages: List<Message>?, count: Int): Set<Message> {
            val recent: MutableSet<Message> = Collections.newSetFromMap(IdentityHashMap())
            if (allMessages == null || count <= 0) return recent
            var matched = 0
            var i = allMessages.size - 1
            while (i >= 0 && matched < count) {
                val m = allMessages[i]
                if (m.role == Message.ROLE_ASSISTANT) {
                    recent.add(m)
                    matched++
                }
                i--
            }
            return recent
        }
    }

    class ActionPanelStateStore {
        // Unified 3-level state for all messages (0=collapsed, 1=primary, 2=secondary)
        private val levels: MutableMap<Message, Int> = IdentityHashMap()
        private val assistantExpandStack: Deque<Message> = ArrayDeque()
        private val activeMessages: MutableSet<Message> = Collections.newSetFromMap(IdentityHashMap())
        // Messages whose level was changed by a user action; they are immune to
        // future auto-fold passes. New incoming messages are not in this set,
        // so applyAutoFold() can demote the previous "latest" assistant turn.
        private val userTouched: MutableSet<Message> = Collections.newSetFromMap(IdentityHashMap())

        fun onAllMessagesChanged(allMessages: List<Message>?) {
            activeMessages.clear()
            if (allMessages != null) activeMessages.addAll(allMessages)
            levels.keys.retainAll(activeMessages)
            assistantExpandStack.removeIf { item -> item == null || !activeMessages.contains(item) }
            userTouched.retainAll(activeMessages)
        }

        /**
         * For assistant messages only: demote every non-touched assistant turn
         * to level 0 and expand the very latest one to level 1. Returns the
         * list of messages whose level changed (caller can `notifyItemChanged`).
         */
        fun applyAutoFold(allMessages: List<Message>): List<Message> {
            val changed: MutableList<Message> = ArrayList()
            var latestAssistant: Message? = null
            for (i in allMessages.indices.reversed()) {
                val m = allMessages[i]
                if (m.role == Message.ROLE_ASSISTANT) { latestAssistant = m; break }
            }
            for (m in allMessages) {
                if (m.role != Message.ROLE_ASSISTANT) continue
                if (userTouched.contains(m)) continue
                val target = if (m === latestAssistant) 1 else 0
                if ((levels[m] ?: 0) != target) {
                    levels[m] = target
                    changed.add(m)
                    if (m.role == Message.ROLE_ASSISTANT) {
                        assistantExpandStack.removeIf { it === m }
                        if (target > 0) assistantExpandStack.addFirst(m)
                    }
                }
            }
            return changed
        }

        fun getLevel(message: Message?): Int = if (message == null) 0 else levels[message] ?: 0

        // Aliases kept for call sites
        fun getUserLevel(message: Message?): Int = getLevel(message)

        /** Toggle: 0 (collapsed) ↔ 1 (expanded). Single-level fold, no cycle. */
        fun cycleLevel(message: Message?): List<Message> {
            val changed: MutableList<Message> = ArrayList()
            if (message == null) return changed
            val next = if (getLevel(message) == 0) 1 else 0
            levels[message] = next
            userTouched.add(message)
            changed.add(message)
            if (message.role == Message.ROLE_ASSISTANT) {
                assistantExpandStack.removeIf { it === message }
                if (next > 0) {
                    assistantExpandStack.addFirst(message)
                    evictAssistant(changed)
                }
            }
            return changed
        }

        fun cycleUserLevel(message: Message?): List<Message> = cycleLevel(message)

        fun isExpanded(message: Message?): Boolean = getLevel(message) > 0

        /** Expand to level 1 if currently collapsed (used for pinned messages) */
        fun expand(message: Message?): List<Message> {
            val changed: MutableList<Message> = ArrayList()
            if (message == null || isExpanded(message)) return changed
            levels[message] = 1
            userTouched.add(message)
            changed.add(message)
            if (message.role == Message.ROLE_ASSISTANT) {
                assistantExpandStack.removeIf { it === message }
                assistantExpandStack.addFirst(message)
                evictAssistant(changed)
            }
            return changed
        }

        private fun evictAssistant(changed: MutableList<Message>) {
            while (assistantExpandStack.size > MAX_EXPANDED_ASSISTANT_ACTIONS) {
                val removed = assistantExpandStack.removeLast() ?: continue
                if (getLevel(removed) > 0) {
                    levels[removed] = 0
                    changed.add(removed)
                }
            }
        }
    }

    fun setOnMessageActionListener(listener: OnMessageActionListener?) {
        this.actionListener = listener
    }

    fun setOnAssistantStateChangedListener(listener: OnAssistantStateChangedListener?) {
        this.assistantStateChangedListener = listener
    }

    fun setWriterMode(enabled: Boolean) {
        writerMode = enabled
        notifyDataSetChanged()
    }

    fun setDisableAssistantCollapseToggle(disabled: Boolean) {
        disableAssistantCollapseToggle = disabled
        notifyDataSetChanged()
    }

    fun setCharacterMode(enabled: Boolean) {
        if (characterMode == enabled) return
        characterMode = enabled
        markdownRenderedSource.clear()
        markdownLastRenderAt.clear()
        notifyDataSetChanged()
    }

    fun setAutoFocusLatestOnSetMessages(enabled: Boolean) {
        autoFocusLatestOnSetMessages = enabled
    }

    fun setCollapseToggleAffixViewport(viewportTop: Int, viewportBottom: Int) {
        affixViewportTop = viewportTop
        affixViewportBottom = viewportBottom
        updateCollapseToggleAffixForAttachedHolders()
    }

    fun setMessages(list: List<Message>?) {
        messages.clear()
        if (list != null) {
            messages.addAll(list)
        }
        expandedReasoningMessages.retainAll(messages)
        actionPanelStateStore.onAllMessagesChanged(messages)
        actionPanelStateStore.applyAutoFold(messages)
        markdownRenderedSource.keys.retainAll(messages)
        markdownLastRenderAt.keys.retainAll(messages)
        notifyDataSetChanged()
    }

    fun addMessage(msg: Message) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
        // New message arrived: previous "latest" assistant turn is no longer
        // latest, so demote it (only if user never touched its level).
        if (msg.role == Message.ROLE_ASSISTANT) {
            val changed = actionPanelStateStore.applyAutoFold(messages)
            for (c in changed) {
                if (c === msg) continue
                val idx = messages.indexOfFirst { it === c }
                if (idx >= 0) notifyItemChanged(idx)
            }
        }
    }

    fun notifyMessageChanged(target: Message?): Boolean {
        if (target == null) return false
        for (i in messages.indices) {
            if (messages[i] === target) {
                notifyItemChanged(i, PAYLOAD_STREAM_TICK)
                return true
            }
        }
        return false
    }

    fun renderStreamingMessageIfVisible(target: Message?): Boolean {
        if (target == null) return false
        var rendered = false
        val snapshot: List<AssistantHolder> = ArrayList(attachedAssistantHolders)
        for (h in snapshot) {
            if (h.boundMessage !== target) continue
            val content = target.content ?: ""
            val hasVisibleContent = content.trim().isNotEmpty()
            h.textContent.visibility = if (hasVisibleContent) View.VISIBLE else View.GONE
            if (hasVisibleContent) {
                bindAssistantContentStreaming(h, target, content)
            }
            if (h.lastHasVisibleContent != hasVisibleContent) {
                // Batch visibility-state transitions together to avoid extra layout passes per tick.
                h.layoutAssistantBubble.visibility = if (hasVisibleContent) View.VISIBLE else View.GONE
                h.textCollapseToggle.visibility = if (hasVisibleContent) View.VISIBLE else View.GONE
                h.lastHasVisibleContent = hasVisibleContent
            }
            bindReasoning(h, target, h.bindingAdapterPosition)
            applyCollapseToggleAffix(h)
            rendered = true
        }
        return rendered
    }

    fun setPinnedActionMessages(userMessage: Message?, assistantMessage: Message?, hideAssistantActions: Boolean) {
        this.pinnedUserMessage = userMessage
        this.pinnedAssistantMessage = assistantMessage
        this.hidePinnedAssistantActions = hideAssistantActions
    }

    /**
     * 标记正在流式生成的 AI 消息。设置后该消息底部工具栏会被隐藏，直到流式结束传入 null。
     */
    fun setStreamingAssistantMessage(message: Message?) {
        if (this.streamingAssistantMessage === message) return
        val prev = this.streamingAssistantMessage
        this.streamingAssistantMessage = message
        // 切换流式目标时，刷新 prev / new 两条消息的工具栏可见性。
        prev?.let { notifyMessageChanged(it) }
        message?.let { notifyMessageChanged(it) }
    }

    fun getMessages(): List<Message> {
        return ArrayList(messages)
    }

    fun clearFocus() {
        // Action panel visibility is controlled by explicit expand button.
    }

    override fun getItemViewType(position: Int): Int {
        if (position < 0 || position >= messages.size) return VIEW_USER
        val m = messages[position]
        return if (m.role == Message.ROLE_USER) VIEW_USER else VIEW_ASSISTANT
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        if (markwon == null) {
            // 必须用 Activity context（含 fontScale 覆盖），不能用 applicationContext
            markwon = Markwon.create(ctx)
        }
        val inflater = LayoutInflater.from(ctx)
        return if (viewType == VIEW_USER) {
            val v = inflater.inflate(R.layout.item_message_user, parent, false)
            UserHolder(v).also { h ->
                h.textContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppFontSize.body(ctx))
                h.textTimestamp.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppFontSize.caption(ctx))
            }
        } else {
            val v = inflater.inflate(R.layout.item_message_assistant, parent, false)
            AssistantHolder(v).also { h ->
                h.textContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppFontSize.body(ctx))
                h.textTimestamp.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppFontSize.caption(ctx))
                h.textReasoningContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppFontSize.reasoning(ctx))
                h.textReasoningHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppFontSize.caption(ctx))
            }
        }
    }

    override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
        bindViewHolder(holder, position, true)
    }

    override fun onBindViewHolder(
        @NonNull holder: RecyclerView.ViewHolder,
        position: Int,
        @NonNull payloads: List<Any>
    ) {
        if (payloads.isEmpty()) {
            bindViewHolder(holder, position, true)
            return
        }
        if (hasStreamTickPayload(payloads) && holder is AssistantHolder) {
            bindViewHolder(holder, position, false)
            return
        }
        bindViewHolder(holder, position, true)
    }

    private fun bindViewHolder(holder: RecyclerView.ViewHolder, position: Int, fullBind: Boolean) {
        if (position < 0 || position >= messages.size) return
        val m = messages[position]
        val content = m.content ?: ""
        val pinnedUser = m === pinnedUserMessage
        val pinnedAssistant = m === pinnedAssistantMessage

        if (holder is UserHolder) {
            holder.textTimestamp.text = formatTimestamp(m.createdAt)
            holder.textContent.text = content
            holder.itemView.setOnClickListener(null)
            holder.textContent.setOnLongClickListener {
                showUserActionPopup(holder.textContent, m)
                true
            }
        } else if (holder is AssistantHolder) {
            holder.boundMessage = m
            holder.textTimestamp.text = formatTimestamp(m.createdAt)
            holder.textContent.alpha = 1f
            val isMemoryLoadingPlaceholder =
                m.role == Message.ROLE_ASSISTANT &&
                CHARACTER_MEMORY_LOADING_TEXT == content.trim()
            if (isMemoryLoadingPlaceholder) {
                holder.textContent.visibility = View.VISIBLE
                holder.textContent.text = CHARACTER_MEMORY_LOADING_TEXT
                holder.textContent.maxLines = 1
                holder.textContent.ellipsize = TextUtils.TruncateAt.END
                holder.textContent.alpha = 0.72f
                holder.layoutReasoning.visibility = View.GONE
                holder.textUsage.visibility = View.GONE
                holder.actionExpand.visibility = View.GONE
                holder.layoutActions.visibility = View.GONE
                holder.textCollapseToggle.visibility = View.GONE
                if (fullBind) holder.itemView.setOnClickListener(null)
                return
            }
            val isLatest = isLatestAssistantMessage(m)
            var expanded = assistantStateStore.isExpanded(m)
            if (disableAssistantCollapseToggle) expanded = true
            val hasVisibleContent = content.trim().isNotEmpty()
            // 3-level: 0(>>) → 1(> + open/voice/outline) → 2(< + delete/edit/copy) → 0
            val rawLevel = if (pinnedAssistant && !hidePinnedAssistantActions)
                maxOf(actionPanelStateStore.getLevel(m), 1)
            else actionPanelStateStore.getLevel(m)
            // Single-level fold: latest is always expanded (rawLevel forced to 1).
            val toolbarExpanded = isLatest || rawLevel >= 1
            // 最新一条 AI 消息工具栏永远展开，折叠箭头无意义 → 隐藏；
            // 流式生成中的 AI 消息整条工具栏隐藏。
            val isStreaming = streamingAssistantMessage != null && m === streamingAssistantMessage
            val showActions = toolbarExpanded && !isStreaming
            holder.actionExpand.setImageResource(
                if (toolbarExpanded) R.drawable.ic_action_expand_left
                else R.drawable.ic_chevron_right
            )
            holder.actionExpand.scaleX = 1f
            holder.actionExpand.visibility = if (isLatest) View.GONE else View.VISIBLE
            holder.layoutActions.visibility = if (showActions) View.VISIBLE else View.GONE
            holder.actionOutline.visibility = if (writerMode && showActions) View.VISIBLE else View.GONE
            holder.actionVoicePlay.visibility = if (characterMode && showActions) View.VISIBLE else View.GONE
            if (fullBind || holder.lastHasVisibleContent != hasVisibleContent) {
                if (disableAssistantCollapseToggle) {
                    holder.textCollapseToggle.visibility = View.GONE
                } else {
                    holder.textCollapseToggle.visibility = if (hasVisibleContent) View.VISIBLE else View.GONE
                }
                holder.lastHasVisibleContent = hasVisibleContent
            }
            if (!disableAssistantCollapseToggle) {
                setCollapseToggleLabel(holder, expanded)
            }
            if (disableAssistantCollapseToggle) {
                holder.textCollapseToggle.setOnClickListener(null)
            }
            holder.textContent.visibility = if (hasVisibleContent) View.VISIBLE else View.GONE
            holder.layoutAssistantBubble.visibility = if (hasVisibleContent) View.VISIBLE else View.GONE
            if (hasVisibleContent) bindAssistantContent(holder, m, content, expanded)
            bindReasoning(holder, m, position)
            applyCollapseToggleAffix(holder)
            // Re-apply once after the view has been measured/laid-out (height is
            // 0 during initial bind; this single post settles the pill exactly
            // and never repeats).
            holder.itemView.post {
                applyCollapseToggleAffix(holder)
                maybeHideShortMessageCollapseToggle(holder, m)
            }
            if (fullBind) {
                holder.itemView.setOnClickListener(null)
                holder.actionExpand.setOnClickListener { cycleAssistantActionLevel(m) }
                holder.actionEdit.setOnClickListener { actionListener?.onEdit(m) }
                holder.actionCopy.setOnClickListener { actionListener?.onCopy(m) }
                holder.actionVoicePlay.setOnClickListener { actionListener?.onVoicePlay(m) }
                holder.actionOpen.setOnClickListener { actionListener?.onOpen(m) }
                holder.actionOutline.setOnClickListener { actionListener?.onOutline(m) }
                holder.actionDelete.setOnClickListener { actionListener?.onDelete(m) }
                if (disableAssistantCollapseToggle) {
                    holder.textCollapseToggle.setOnClickListener(null)
                } else {
                    holder.textCollapseToggle.setOnClickListener { toggleAssistantExpanded(holder, m) }
                }
            }
        }
    }

    private fun showUserActionPopup(anchor: View, m: Message) {
        val popup = userActionPopup
            ?: com.example.aichat.widget.MessageActionPopup(anchor.context).also { userActionPopup = it }
        popup.show(anchor, writerMode, object : com.example.aichat.widget.MessageActionPopup.Listener {
            override fun onCopy() { actionListener?.onCopy(m) }
            override fun onEdit() { actionListener?.onEdit(m) }
            override fun onRegenerate() { actionListener?.onRegenerate(m) }
            override fun onOutline() { actionListener?.onOutline(m) }
            override fun onDelete() { actionListener?.onDelete(m) }
        })
    }

    private fun isLatestAssistantMessage(m: Message): Boolean {
        for (i in messages.indices.reversed()) {
            val mm = messages[i]
            if (mm.role == Message.ROLE_ASSISTANT) return mm === m
        }
        return false
    }

    private fun hasStreamTickPayload(payloads: List<Any>): Boolean {
        for (payload in payloads) {
            if (payload === PAYLOAD_STREAM_TICK) return true
        }
        return false
    }

    override fun getItemCount(): Int = messages.size

    override fun onViewAttachedToWindow(@NonNull holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is AssistantHolder) {
            attachedAssistantHolders.add(holder)
            applyCollapseToggleAffix(holder)
        }
    }

    override fun onViewDetachedFromWindow(@NonNull holder: RecyclerView.ViewHolder) {
        if (holder is AssistantHolder) {
            attachedAssistantHolders.remove(holder)
        }
        super.onViewDetachedFromWindow(holder)
    }

    inner class UserHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textTimestamp: TextView = itemView.findViewById(R.id.textTimestamp)
        val textContent: TextView = itemView.findViewById(R.id.textContent)
    }

    inner class AssistantHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textTimestamp: TextView = itemView.findViewById(R.id.textTimestamp)
        val textContent: TextView = itemView.findViewById(R.id.textContent)
        val textCollapseToggle: View = itemView.findViewById(R.id.textCollapseToggle)
        val textCollapseIcon: ImageView = itemView.findViewById(R.id.textCollapseIcon)
        val textCollapseLabel: TextView = itemView.findViewById(R.id.textCollapseLabel)
        val layoutAssistantBubble: View = itemView.findViewById(R.id.layoutAssistantBubble)
        val actionExpand: ImageView = itemView.findViewById(R.id.actionExpand)
        val layoutActions: View = itemView.findViewById(R.id.layoutActions)
        val layoutReasoning: View = itemView.findViewById(R.id.layoutReasoning)
        val textReasoningHeader: TextView = itemView.findViewById(R.id.textReasoningHeader)
        val textReasoningContent: TextView = itemView.findViewById(R.id.textReasoningContent)
        val textUsage: TextView = itemView.findViewById(R.id.textUsage)
        val actionEdit: View = itemView.findViewById(R.id.actionEdit)
        val actionCopy: View = itemView.findViewById(R.id.actionCopy)
        val actionOpen: View = itemView.findViewById(R.id.actionOpen)
        val actionVoicePlay: ImageView = itemView.findViewById(R.id.actionVoicePlay)
        val actionOutline: View = itemView.findViewById(R.id.actionOutline)
        val actionDelete: View = itemView.findViewById(R.id.actionDelete)
        var lastHasVisibleContent: Boolean = false
        var boundMessage: Message? = null
    }

    private fun cycleAssistantActionLevel(message: Message?) {
        if (message == null) return
        val changed = actionPanelStateStore.cycleLevel(message)
        for (one in changed) {
            val idx = indexOfMessage(one)
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    private fun expandActionPanel(message: Message?) {
        if (message == null) return
        val changed = actionPanelStateStore.expand(message)
        if (changed.isEmpty()) return
        for (one in changed) {
            val idx = indexOfMessage(one)
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    private fun indexOfMessage(target: Message?): Int {
        if (target == null) return -1
        for (i in messages.indices) {
            if (messages[i] === target) return i
        }
        return -1
    }

    private fun bindReasoning(h: AssistantHolder, m: Message?, position: Int) {
        val hasReasoning = m != null && m.reasoning.trim().isNotEmpty()
        val hasThinkingState = m != null && (m.thinkingRunning || m.thinkingElapsedMs > 0 || hasReasoning)
        val hasUsage = m != null && (m.totalTokens > 0 || m.elapsedMs > 0)
        h.layoutReasoning.visibility = if (hasThinkingState) View.VISIBLE else View.GONE
        if (hasThinkingState) {
            h.textReasoningHeader.visibility = View.VISIBLE
            val expanded = m != null && expandedReasoningMessages.contains(m)
            val thinkingTime = formatSeconds(m?.thinkingElapsedMs ?: 0)
            h.textReasoningHeader.text = (if (expanded) "Thinking \u25b2 " else "Thinking \u25bc ") + thinkingTime
            val reasoning = m?.reasoning
            val display = if (reasoning == null || reasoning.trim().isEmpty()) "Thinking 中..." else reasoning
            h.textReasoningContent.visibility = View.VISIBLE
            h.textReasoningContent.text = display
            if (expanded) {
                h.textReasoningContent.maxLines = Int.MAX_VALUE
                h.textReasoningContent.ellipsize = null
            } else {
                // Collapsed preview: show up to two lines.
                h.textReasoningContent.maxLines = 2
                h.textReasoningContent.ellipsize = TextUtils.TruncateAt.END
            }
            h.textReasoningHeader.setOnClickListener {
                if (m == null) return@setOnClickListener
                if (expandedReasoningMessages.contains(m)) {
                    expandedReasoningMessages.remove(m)
                } else {
                    expandedReasoningMessages.add(m)
                }
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    notifyItemChanged(p)
                }
            }
        } else {
            h.textReasoningHeader.visibility = View.GONE
            h.textReasoningContent.visibility = View.GONE
            h.textReasoningHeader.setOnClickListener(null)
            if (m != null) {
                expandedReasoningMessages.remove(m)
            }
        }
        if (hasUsage && m != null) {
            val usage = "tokens: " + m.totalTokens +
                    "（in " + maxOf(m.promptTokens, 0) +
                    " / out " + maxOf(m.completionTokens, 0) + "）" +
                    "  耗时: " + formatSeconds(m.elapsedMs)
            h.textUsage.text = usage
            h.textUsage.visibility = View.VISIBLE
        } else {
            h.textUsage.visibility = View.GONE
        }
    }

    private fun toggleAssistantExpanded(h: AssistantHolder, m: Message?) {
        if (m == null || m.role != Message.ROLE_ASSISTANT) return
        val changed = assistantStateStore.toggle(m)
        if (changed.isEmpty()) return
        for (one in changed) {
            markdownRenderedSource.remove(one)
            markdownLastRenderAt.remove(one)
        }
        val p = h.bindingAdapterPosition
        if (p != RecyclerView.NO_POSITION) notifyItemChanged(p)
        assistantStateChangedListener?.onAssistantStateChanged()
    }

    private fun bindAssistantContent(h: AssistantHolder, m: Message?, content: String, expanded: Boolean) {
        if (!expanded) {
            h.textContent.maxLines = 3
            h.textContent.ellipsize = TextUtils.TruncateAt.END
        } else {
            h.textContent.maxLines = Int.MAX_VALUE
            h.textContent.ellipsize = null
        }
        if (characterMode) {
            h.textContent.text = buildCharacterDisplay(h.textContent, content)
            return
        }
        if (markwon == null || m == null) {
            h.textContent.text = content
            return
        }
        val lastSource = markdownRenderedSource[m]
        val lastAt = markdownLastRenderAt[m] ?: 0L
        val now = System.currentTimeMillis()
        val contentChanged = lastSource == null || content != lastSource
        val canRenderMarkdownNow = !contentChanged || (now - lastAt >= MARKDOWN_RENDER_THROTTLE_MS)
        if (!canRenderMarkdownNow) {
            // Keep last rendered markdown to avoid flashing between plain text and markdown spans.
            if (lastSource == null) {
                h.textContent.text = content
            }
            return
        }
        markwon!!.setMarkdown(h.textContent, content)
        markdownRenderedSource[m] = content
        markdownLastRenderAt[m] = now
    }

    private fun bindAssistantContentStreaming(h: AssistantHolder, m: Message?, content: String) {
        var expanded = m != null && assistantStateStore.isExpanded(m)
        if (disableAssistantCollapseToggle) expanded = true
        if (!expanded) {
            h.textContent.text = if (characterMode) buildCharacterDisplay(h.textContent, content) else content
            h.textContent.maxLines = 3
            h.textContent.ellipsize = TextUtils.TruncateAt.END
            return
        }
        h.textContent.maxLines = Int.MAX_VALUE
        h.textContent.ellipsize = null
        h.textContent.text = if (characterMode) buildCharacterDisplay(h.textContent, content) else content
    }

    /**
     * 角色模式渲染：解析协议 emoji 后隐藏，括号段落用 ios_section_label 灰色。
     */
    private fun buildCharacterDisplay(anchor: TextView, content: String): CharSequence {
        if (content.isEmpty()) return content
        val parsed = EmotionTagParser.parse(content)
        val display = parsed.displayText
        if (parsed.narrationRanges.isEmpty()) return display
        val color = ContextCompat.getColor(anchor.context, R.color.ios_section_label)
        val span = SpannableString(display)
        for (range in parsed.narrationRanges) {
            val end = (range.last + 1).coerceAtMost(display.length)
            val start = range.first.coerceAtLeast(0)
            if (start >= end) continue
            span.setSpan(
                ForegroundColorSpan(color),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return span
    }

    private fun updateCollapseToggleAffixForAttachedHolders() {
        val snapshot: List<AssistantHolder> = ArrayList(attachedAssistantHolders)
        for (h in snapshot) {
            applyCollapseToggleAffix(h)
        }
    }

    /**
     * Pin the floating "expand / collapse" pill to the vertical center of the
     * portion of the bubble that's visible inside the RecyclerView's viewport.
     * When the bubble is fully on-screen → the pill sits in the geometric
     * center of the bubble. When the bubble extends beyond the viewport → the
     * pill clamps to the visible slice's center, so it never scrolls out.
     *
     * No-op if viewport hasn't been wired up yet (affixViewportTop sentinel).
     */
    /**
     * Float the "expand / collapse" pill so it stays reachable while the
     * user scrolls a long assistant bubble.
     *
     * Default anchor (handled entirely by layout, no work here): bottom|end of
     * the bubble with 6dp margin. While the bubble's bottom is inside the
     * viewport — including the common "streaming + auto-scroll-to-bottom"
     * case where bubble bottom equals viewport bottom — translationY is 0 so
     * the pill never moves and never causes invalidation.
     *
     * Only when the bubble's bottom has scrolled below the viewport does this
     * lift the pill upward, just enough to keep it inside the viewport,
     * clamped so it never escapes the bubble.
     *
     * Bubble fully off-screen → no-op; we leave the last translationY as-is to
     * avoid wasted invalidations.
     */
    private fun applyCollapseToggleAffix(h: AssistantHolder) {
        val toggle = h.textCollapseToggle
        if (toggle.visibility != View.VISIBLE) return
        if (affixViewportTop == Int.MIN_VALUE || affixViewportBottom == Int.MIN_VALUE) return
        val bubble = h.layoutAssistantBubble
        if (bubble.height <= 0 || toggle.height <= 0) return
        val pos = IntArray(2)
        bubble.getLocationOnScreen(pos)
        val bubbleTopAbs = pos[1]
        val bubbleBottomAbs = bubbleTopAbs + bubble.height
        if (bubbleBottomAbs <= affixViewportTop || bubbleTopAbs >= affixViewportBottom) return

        val gapPx = (6f * h.itemView.resources.displayMetrics.density)
        val newY: Float = if (bubbleBottomAbs <= affixViewportBottom) {
            0f
        } else {
            // How far the bubble bottom is below the viewport bottom.
            val pullUp = (bubbleBottomAbs - affixViewportBottom).toFloat()
            // Don't lift past bubble top (toggle would escape upward).
            val maxPullUp = (bubble.height - toggle.height - gapPx * 2f).coerceAtLeast(0f)
            -minOf(pullUp, maxPullUp)
        }
        if (toggle.translationY != newY) toggle.translationY = newY
    }

    /**
     * 历史 AI 消息正文 ≤3 行（无截断）时隐藏浮动折叠胶囊。流式中的消息不处理，避免随内容
     * 增长产生闪烁。必须在 textContent 完成 measure/layout 之后才能可靠读到 lineCount。
     */
    private fun maybeHideShortMessageCollapseToggle(holder: AssistantHolder, m: Message?) {
        if (m == null || m === streamingAssistantMessage) return
        if (disableAssistantCollapseToggle) return
        if (holder.textCollapseToggle.visibility != View.VISIBLE) return
        val tv = holder.textContent
        val layout = tv.layout ?: return
        val lineCount = layout.lineCount
        val textLen = tv.text?.length ?: 0
        val lastEnd = if (lineCount > 0) layout.getLineEnd(lineCount - 1) else 0
        val truncated = lastEnd < textLen
        val moreThanThree = lineCount > 3
        if (!truncated && !moreThanThree) {
            holder.textCollapseToggle.visibility = View.GONE
        }
    }

    private fun setCollapseToggleLabel(holder: AssistantHolder, expanded: Boolean) {
        holder.textCollapseIcon.setImageResource(
            if (expanded) R.drawable.ic_collapse_expand_less
            else R.drawable.ic_collapse_expand_more
        )
        holder.textCollapseLabel.setText(
            if (expanded) R.string.collapse_message else R.string.expand_message
        )
    }

    private fun formatSeconds(ms: Long): String {
        return String.format(Locale.US, "%.1fs", maxOf(ms, 0) / 1000.0f)
    }

    fun updateVoicePlayState(playingMessageId: Long?) {
        for (h in ArrayList(attachedAssistantHolders)) {
            val msg = h.boundMessage ?: continue
            val isPlaying = playingMessageId != null && msg.id == playingMessageId
            h.actionVoicePlay.setImageResource(
                if (isPlaying) R.drawable.ic_action_voice_stop else R.drawable.ic_action_voice_play
            )
        }
    }

    private fun formatTimestamp(createdAt: Long): String {
        if (createdAt <= 0) return ""
        return if (isSameDayAsNow(createdAt)) timestampTodayFormat.format(createdAt)
               else timestampFormat.format(createdAt)
    }

    private fun isSameDayAsNow(createdAt: Long): Boolean {
        val cal = java.util.Calendar.getInstance()
        val nowYear = cal.get(java.util.Calendar.YEAR)
        val nowDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = createdAt
        return cal.get(java.util.Calendar.YEAR) == nowYear &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == nowDay
    }
}
