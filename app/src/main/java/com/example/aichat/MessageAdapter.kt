package com.example.aichat

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
    private var actionListener: OnMessageActionListener? = null
    private val timestampFormat: SimpleDateFormat
    private val assistantStateStore: AssistantMarkdownStateStore
    private val actionPanelStateStore: ActionPanelStateStore
    private var assistantStateChangedListener: OnAssistantStateChangedListener? = null
    private var markwon: Markwon? = null
    private val markdownRenderedSource: MutableMap<Message, String> = IdentityHashMap()
    private val markdownLastRenderAt: MutableMap<Message, Long> = IdentityHashMap()
    private val attachedAssistantHolders: MutableSet<AssistantHolder> =
        Collections.newSetFromMap(IdentityHashMap())
    private var writerMode: Boolean = false
    private var disableAssistantCollapseToggle: Boolean = false
    private var autoFocusLatestOnSetMessages: Boolean = true
    private var affixViewportTop: Int = Int.MIN_VALUE
    private var affixViewportBottom: Int = Int.MIN_VALUE

    constructor() : this(AssistantMarkdownStateStore(), ActionPanelStateStore())

    constructor(stateStore: AssistantMarkdownStateStore) : this(stateStore, ActionPanelStateStore())

    constructor(stateStore: AssistantMarkdownStateStore?, actionStore: ActionPanelStateStore?) {
        this.assistantStateStore = stateStore ?: AssistantMarkdownStateStore()
        this.actionPanelStateStore = actionStore ?: ActionPanelStateStore()
        timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        timestampFormat.timeZone = TimeZone.getDefault()
    }

    interface OnMessageActionListener {
        fun onRegenerate(message: Message)
        fun onEdit(message: Message)
        fun onCopy(message: Message)
        fun onOpen(message: Message)
        fun onOutline(message: Message)
        fun onDelete(message: Message)
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
        private val expanded: MutableSet<Message> = Collections.newSetFromMap(IdentityHashMap())
        private val assistantExpandStack: Deque<Message> = ArrayDeque()
        private val activeMessages: MutableSet<Message> = Collections.newSetFromMap(IdentityHashMap())

        fun onAllMessagesChanged(allMessages: List<Message>?) {
            activeMessages.clear()
            if (allMessages != null) activeMessages.addAll(allMessages)
            expanded.retainAll(activeMessages)
            assistantExpandStack.removeIf { item -> item == null || !expanded.contains(item) }
        }

        fun isExpanded(message: Message?): Boolean {
            return message != null && expanded.contains(message)
        }

        fun expand(message: Message?): List<Message> {
            val changed: MutableList<Message> = ArrayList()
            if (message == null) return changed
            if (expanded.contains(message)) return changed
            expanded.add(message)
            changed.add(message)
            // Only assistant messages participate in "max 3 expanded" eviction.
            if (message.role == Message.ROLE_ASSISTANT) {
                removeFromAssistantStack(message)
                assistantExpandStack.addFirst(message)
                while (assistantExpandStack.size > MAX_EXPANDED_ASSISTANT_ACTIONS) {
                    val removed = assistantExpandStack.removeLast() ?: continue
                    expanded.remove(removed)
                    changed.add(removed)
                }
            }
            return changed
        }

        private fun removeFromAssistantStack(message: Message) {
            assistantExpandStack.removeIf { item -> item === message }
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
        markdownRenderedSource.keys.retainAll(messages)
        markdownLastRenderAt.keys.retainAll(messages)
        notifyDataSetChanged()
    }

    fun addMessage(msg: Message) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
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
        if (markwon == null) {
            markwon = Markwon.create(parent.context.applicationContext)
        }
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_USER) {
            val v = inflater.inflate(R.layout.item_message_user, parent, false)
            UserHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_message_assistant, parent, false)
            AssistantHolder(v)
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
        val expandedByUser = actionPanelStateStore.isExpanded(m)
        val pinnedUser = m === pinnedUserMessage
        val pinnedAssistant = m === pinnedAssistantMessage
        val showActions = expandedByUser || pinnedUser || (pinnedAssistant && !hidePinnedAssistantActions)

        if (holder is UserHolder) {
            holder.textTimestamp.text = formatTimestamp(m.createdAt)
            holder.textContent.text = content
            holder.layoutActions.visibility = if (showActions) View.VISIBLE else View.GONE
            holder.actionExpand.alpha = if (showActions) 0.55f else 1f
            holder.actionExpand.rotation = if (showActions) 180f else 0f
            // 每次重新绑定时收起次级菜单
            holder.layoutMoreActions.visibility = View.GONE
            // 小说大纲仅在写作模式下显示（在 layoutMoreActions 内）
            holder.actionOutline.visibility = if (writerMode) View.VISIBLE else View.GONE
            holder.actionExpand.setOnClickListener { expandActionPanel(m) }
            holder.itemView.setOnClickListener(null)
            holder.actionRegenerate.setOnClickListener { actionListener?.onRegenerate(m) }
            holder.actionCopy.setOnClickListener { actionListener?.onCopy(m) }
            holder.actionMore.setOnClickListener {
                holder.layoutMoreActions.visibility =
                    if (holder.layoutMoreActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            holder.actionEdit.setOnClickListener { actionListener?.onEdit(m) }
            holder.actionOutline.setOnClickListener { actionListener?.onOutline(m) }
            holder.actionDelete.setOnClickListener { actionListener?.onDelete(m) }
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
            var expanded = assistantStateStore.isExpanded(m)
            if (disableAssistantCollapseToggle) expanded = true
            val hasVisibleContent = content.trim().isNotEmpty()
            holder.layoutActions.visibility = if (showActions) View.VISIBLE else View.GONE
            holder.actionExpand.visibility = View.VISIBLE
            holder.actionExpand.alpha = if (showActions) 0.55f else 1f
            holder.actionExpand.rotation = if (showActions) 180f else 0f
            if (fullBind || holder.lastHasVisibleContent != hasVisibleContent) {
                if (disableAssistantCollapseToggle) {
                    holder.textCollapseToggle.visibility = View.GONE
                } else {
                    holder.textCollapseToggle.visibility = if (hasVisibleContent) View.VISIBLE else View.GONE
                }
                holder.lastHasVisibleContent = hasVisibleContent
            }
            if (!disableAssistantCollapseToggle) {
                setCollapseToggleLabel(holder.textCollapseToggle, expanded)
            }
            if (disableAssistantCollapseToggle) {
                // Character chats do not use assistant content collapse controls at all.
                holder.textCollapseToggle.setOnClickListener(null)
            }
            holder.textContent.visibility = if (hasVisibleContent) View.VISIBLE else View.GONE
            if (hasVisibleContent) bindAssistantContent(holder, m, content, expanded)
            holder.actionOutline.visibility = if (writerMode && showActions) View.VISIBLE else View.INVISIBLE
            holder.actionEdit.visibility = if (showActions) View.VISIBLE else View.INVISIBLE
            holder.actionCopy.visibility = if (showActions) View.VISIBLE else View.INVISIBLE
            holder.actionOpen.visibility = if (showActions) View.VISIBLE else View.INVISIBLE
            holder.actionDelete.visibility = if (showActions) View.VISIBLE else View.INVISIBLE
            bindReasoning(holder, m, position)
            applyCollapseToggleAffix(holder)
            if (fullBind) {
                holder.itemView.setOnClickListener(null)
                holder.actionExpand.setOnClickListener { expandActionPanel(m) }
                holder.actionEdit.setOnClickListener { actionListener?.onEdit(m) }
                holder.actionCopy.setOnClickListener { actionListener?.onCopy(m) }
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
        val layoutActions: View = itemView.findViewById(R.id.layoutActions)
        val actionExpand: View = itemView.findViewById(R.id.actionExpand)
        val actionRegenerate: View = itemView.findViewById(R.id.actionRegenerate)
        val actionCopy: View = itemView.findViewById(R.id.actionCopy)
        val actionMore: View = itemView.findViewById(R.id.actionMore)
        val layoutMoreActions: View = itemView.findViewById(R.id.layoutMoreActions)
        val actionEdit: View = itemView.findViewById(R.id.actionEdit)
        val actionOutline: View = itemView.findViewById(R.id.actionOutline)
        val actionDelete: View = itemView.findViewById(R.id.actionDelete)
    }

    inner class AssistantHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textTimestamp: TextView = itemView.findViewById(R.id.textTimestamp)
        val textContent: TextView = itemView.findViewById(R.id.textContent)
        val textCollapseToggle: ImageView = itemView.findViewById(R.id.textCollapseToggle)
        val layoutAssistantBubble: View = itemView.findViewById(R.id.layoutAssistantBubble)
        val layoutActions: View = itemView.findViewById(R.id.layoutActions)
        val actionExpand: View = itemView.findViewById(R.id.actionExpand)
        val layoutReasoning: View = itemView.findViewById(R.id.layoutReasoning)
        val textReasoningHeader: TextView = itemView.findViewById(R.id.textReasoningHeader)
        val textReasoningContent: TextView = itemView.findViewById(R.id.textReasoningContent)
        val textUsage: TextView = itemView.findViewById(R.id.textUsage)
        val actionEdit: View = itemView.findViewById(R.id.actionEdit)
        val actionCopy: View = itemView.findViewById(R.id.actionCopy)
        val actionOpen: View = itemView.findViewById(R.id.actionOpen)
        val actionOutline: View = itemView.findViewById(R.id.actionOutline)
        val actionDelete: View = itemView.findViewById(R.id.actionDelete)
        var lastHasVisibleContent: Boolean = false
        var boundMessage: Message? = null
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
                // Collapsed preview: render only the first visible line.
                h.textReasoningContent.maxLines = 1
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
            h.textContent.text = content
            h.textContent.maxLines = 3
            h.textContent.ellipsize = TextUtils.TruncateAt.END
            return
        }
        h.textContent.maxLines = Int.MAX_VALUE
        h.textContent.ellipsize = null
        h.textContent.text = content
    }

    private fun updateCollapseToggleAffixForAttachedHolders() {
        val snapshot: List<AssistantHolder> = ArrayList(attachedAssistantHolders)
        for (h in snapshot) {
            applyCollapseToggleAffix(h)
        }
    }

    private fun applyCollapseToggleAffix(h: AssistantHolder) {
        // Toggle is now in the action bar row; no affix positioning needed.
    }

    private fun setCollapseToggleLabel(toggle: ImageView?, expanded: Boolean) {
        if (toggle == null) return
        toggle.setImageResource(if (expanded) R.drawable.ic_collapse_expand_less else R.drawable.ic_collapse_expand_more)
    }

    private fun formatSeconds(ms: Long): String {
        return String.format(Locale.US, "%.1fs", maxOf(ms, 0) / 1000.0f)
    }

    private fun formatTimestamp(createdAt: Long): String {
        if (createdAt <= 0) return ""
        return timestampFormat.format(createdAt)
    }
}
