package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.example.aichat.chat.ChatTimeContext
import com.example.aichat.chat.ProactiveChatPlanner
import com.example.aichat.chat.ProactiveMeta
import com.example.aichat.chat.ProactivePromptBuilder
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ViewModel for ChatSessionActivity.
 *
 * Owns: executor, db, chatService, session state (messages, responseToken, chatHandle).
 * Activity owns: streaming UI (typewriter, thinking ticker, scroll), all dialog flows.
 */
class ChatViewModel(@NonNull application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val INITIAL_RENDER_MESSAGE_LIMIT = 200
        private const val LOAD_MORE_BATCH_SIZE = 50
    }

    // --- LiveData observed by Activity ---
    @JvmField val messages: MutableLiveData<List<Message>> = MutableLiveData(ArrayList())
    @JvmField val responseInProgress: MutableLiveData<Boolean> = MutableLiveData(false)
    /** One-shot error event; Activity should consume and show as Toast. */
    @JvmField val errorEvent: MutableLiveData<String> = MutableLiveData()
    @JvmField val sessionTitle: MutableLiveData<String> = MutableLiveData()
    @JvmField val chatOptions: MutableLiveData<SessionChatOptions> = MutableLiveData()
    @JvmField val hasMoreOlderMessages: MutableLiveData<Boolean> = MutableLiveData(false)
    @JvmField val olderRemainingCount: MutableLiveData<Int> = MutableLiveData(0)
    /** Streaming delta events; Activity applies to UI typewriter. */
    @JvmField val streamDeltaEvent: MutableLiveData<StreamDeltaEvent> = MutableLiveData()
    /**
     * 自动对话 split / follow-up 的增量事件. Activity 用它做 in-place 替换 / 追加,
     * 不必走完整的 loadMessages → DB re-read 路径. 主线程 LiveData.
     */
    @JvmField val proactiveMessageEvent: MutableLiveData<ProactiveMessageEvent> = MutableLiveData()

    // --- Internal state ---
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val db: AppDatabase = AppDatabase.getInstance(application)
    private val chatService: ChatService = ChatService(application)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Concurrent queue that buffers ALL streaming events so none are lost.
     * LiveData.postValue() coalesces rapid updates (only last value survives),
     * which drops intermediate content deltas and causes visible missing characters.
     * Instead, events are enqueued here and streamDeltaEvent serves only as a
     * notification signal; the Activity drains this queue on each observation.
     */
    private val pendingStreamEvents = ConcurrentLinkedQueue<StreamDeltaEvent>()

    private var sessionId: String? = null

    @Volatile private var activeResponseToken: Long = 0
    @Volatile private var activeChatHandle: ChatService.ChatHandle? = null
    @Volatile private var loadingOlderMessages: Boolean = false
    private var oldestLoadedCreatedAt: Long = Long.MAX_VALUE
    private var oldestLoadedMessageId: Long = Long.MAX_VALUE

    /** Lazy-built; only when a session has autoChat on. Per-VM (per-session) singleton. */
    @Volatile private var planner: ProactiveChatPlanner? = null

    private fun ensurePlanner(): ProactiveChatPlanner {
        return planner ?: synchronized(this) {
            planner ?: ProactiveChatPlanner(
                context = getApplication(),
                executor = executor,
                db = db,
                onMessageReplaced = { rowId, newContent ->
                    proactiveMessageEvent.postValue(
                        ProactiveMessageEvent.replace(rowId, newContent)
                    )
                },
                onMessageAppended = { msg ->
                    proactiveMessageEvent.postValue(
                        ProactiveMessageEvent.append(msg)
                    )
                }
            ).also { planner = it }
        }
    }

    /** Call once from Activity.onCreate; idempotent on config change. */
    fun init(sessionId: String) {
        if (this.sessionId != null) return // already initialized (survived config change)
        this.sessionId = sessionId
        loadMessages()
    }

    // ─────────────────────────── Message Loading ───────────────────────────

    fun loadMessages() {
        val sid = sessionId ?: return
        executor.execute {
            var list: List<Message> = ArrayList()
            var oldestCreatedAt = Long.MAX_VALUE
            var oldestMsgId = Long.MAX_VALUE
            var olderCount = 0
            try {
                val desc = db.messageDao().getLatestBySession(sid, INITIAL_RENDER_MESSAGE_LIMIT)
                list = toAscending(desc)
                if (list.isNotEmpty()) {
                    val oldest = list[0]
                    oldestCreatedAt = oldest.createdAt
                    oldestMsgId = oldest.id
                    olderCount = db.messageDao().countOlderMessages(sid, oldestCreatedAt, oldestMsgId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMessages failed", e)
            }
            oldestLoadedCreatedAt = oldestCreatedAt
            oldestLoadedMessageId = oldestMsgId
            val finalOlderCount = maxOf(0, olderCount)
            loadingOlderMessages = false
            hasMoreOlderMessages.postValue(finalOlderCount > 0)
            olderRemainingCount.postValue(finalOlderCount)
            messages.postValue(ArrayList(list))
        }
    }

    fun loadOlderMessages() {
        if (loadingOlderMessages) return
        val moreAvailable = hasMoreOlderMessages.value
        if (moreAvailable == null || !moreAvailable) return
        if (oldestLoadedCreatedAt == Long.MAX_VALUE || oldestLoadedMessageId == Long.MAX_VALUE) return
        val sid = sessionId ?: return
        loadingOlderMessages = true
        val beforeCreatedAt = oldestLoadedCreatedAt
        val beforeMsgId = oldestLoadedMessageId
        executor.execute {
            var olderAsc: List<Message> = ArrayList()
            var newOldest = beforeCreatedAt
            var newOldestMsgId = beforeMsgId
            var remaining = 0
            try {
                val olderDesc = db.messageDao().getOlderBySession(
                    sid, beforeCreatedAt, beforeMsgId, LOAD_MORE_BATCH_SIZE
                )
                olderAsc = toAscending(olderDesc)
                if (olderAsc.isNotEmpty()) {
                    val oldest = olderAsc[0]
                    newOldest = oldest.createdAt
                    newOldestMsgId = oldest.id
                    remaining = db.messageDao().countOlderMessages(sid, newOldest, newOldestMsgId)
                }
            } catch (ignored: Exception) {}
            val finalOlder = olderAsc
            val finalNewOldest = newOldest
            val finalNewOldestMsgId = newOldestMsgId
            val finalRemaining = maxOf(0, remaining)
            if (finalOlder.isNotEmpty()) {
                oldestLoadedCreatedAt = finalNewOldest
                oldestLoadedMessageId = finalNewOldestMsgId
                // Prepend older messages to current list
                val current = messages.value
                val merged: MutableList<Message> = ArrayList(finalOlder)
                if (current != null) merged.addAll(current)
                messages.postValue(merged)
            }
            loadingOlderMessages = false
            hasMoreOlderMessages.postValue(finalRemaining > 0)
            olderRemainingCount.postValue(finalRemaining)
        }
    }

    // ─────────────────────────── Persistence ───────────────────────────

    fun insertMessageAsync(message: Message) {
        executor.execute {
            try {
                db.messageDao().insert(message)
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Insert a message and stamp it for remote sync if [assistantId] is bound.
     * Empty/blank assistantId → leave turnId empty so drainer skips this row.
     */
    fun insertMessageAsync(message: Message, assistantId: String?) {
        stampForSync(message, assistantId)
        insertMessageAsync(message)
    }

    private fun stampForSync(message: Message, assistantId: String?) {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return
        if (message.turnId.isEmpty()) message.turnId = com.example.aichat.sync.UuidV7.next()
        if (message.assistantId.isEmpty()) message.assistantId = aid
        message.synced = 0
    }

    fun persistSessionMessagesAsync(snapshot: List<Message>) {
        val sid = sessionId ?: return
        val copy: List<Message> = ArrayList(snapshot)
        executor.execute {
            try {
                // 仅替换 user/assistant 普通消息 (role 0/1, proactiveKind=0).
                // 保留: role=2 system, role=3 tool_call, role=4 tool_result, proactiveKind != 0 (split / follow-up)
                // — 这些行不在 Activity allMessages snapshot 里, 之前 deleteBySession 会把它们一并 wipe.
                db.messageDao().deleteUserAssistantBySession(sid)
                for (m in copy) {
                    if (m == null) continue
                    // 同样过滤: snapshot 里若混入非普通消息, 不再 reinsert (避免重复 / 失真).
                    if (m.role != Message.ROLE_USER && m.role != Message.ROLE_ASSISTANT) continue
                    if (m.proactiveKind != 0) continue
                    val item = Message(sid, m.role, if (m.content != null) m.content else "")
                    item.createdAt = if (m.createdAt > 0) m.createdAt else System.currentTimeMillis()
                    item.reasoning = m.reasoning ?: ""
                    item.thinkingElapsedMs = m.thinkingElapsedMs
                    db.messageDao().insert(item)
                }
            } catch (ignored: Exception) {}
        }
    }

    fun persistSessionTitle(title: String?, overwriteExisting: Boolean) {
        val trimmed = title?.trim() ?: ""
        if (trimmed.isEmpty()) return
        val finalTitle = trimmed
        val sid = sessionId ?: return
        executor.execute {
            val metaStore = SessionMetaStore(getApplication())
            val meta = metaStore.get(sid)
            val metaTitle = meta.title.trim()
            if (overwriteExisting || metaTitle.isEmpty()) {
                meta.title = finalTitle
                metaStore.save(sid, meta)
            }
            val optionsStore = SessionChatOptionsStore(getApplication())
            val options = optionsStore.get(sid)
            val optionsTitle = options.sessionTitle.trim()
            if (overwriteExisting || optionsTitle.isEmpty()) {
                options.sessionTitle = finalTitle
                optionsStore.save(sid, options)
            }
        }
    }

    // ─────────────────────────── Chat Options ───────────────────────────

    fun resolveChatOptions(assistantId: String?): SessionChatOptions {
        val sid = sessionId ?: ""
        val optionsStore = SessionChatOptionsStore(getApplication())
        val fromSession = optionsStore.get(sid)
        val opts = fromSession ?: SessionChatOptions()
        if (optionsStore.has(sid)) {
            chatOptions.postValue(opts)
            return opts
        }
        val initialized = initializeFromAssistantOrGlobal(opts, assistantId)
        optionsStore.save(sid, initialized)
        chatOptions.postValue(initialized)
        return initialized
    }

    private fun initializeFromAssistantOrGlobal(base: SessionChatOptions, assistantId: String?): SessionChatOptions {
        if (assistantId != null && assistantId.trim().isNotEmpty()) {
            val assistant = MyAssistantStore(getApplication()).getById(assistantId)
            if (assistant != null) {
                val opts = assistant.options
                if (opts != null) return opts
            }
        }
        val configManager = ConfigManager(getApplication())
        val global = SessionChatOptions()
        val globalModelKey = configManager.getModel()
        if (globalModelKey != null && globalModelKey.isNotEmpty()) {
            global.modelKey = globalModelKey
        }
        return global
    }

    // ─────────────────────────── Chat Dispatch ───────────────────────────

    /**
     * @param historyForApi messages to send as context (not including current user message)
     * @param plainApiUserMessage user message text (before memory enrichment)
     * @param options session chat options
     * @param responseToken caller's response token for staleness check
     * @param shouldUseCharacterMemory whether to fetch character memory
     * @param assistantId for character memory calls
     * @param characterMemoryService for memory fetch
     */
    fun dispatchChat(
        historyForApi: List<Message>,
        plainApiUserMessage: String,
        options: SessionChatOptions,
        responseToken: Long,
        shouldUseCharacterMemory: Boolean,
        assistantId: String?,
        characterMemoryService: CharacterMemoryService
    ) {
        activeResponseToken = responseToken
        if (!shouldUseCharacterMemory) {
            doChatRequest(historyForApi, plainApiUserMessage, options, responseToken, false,
                assistantId, characterMemoryService)
            return
        }
        executor.execute {
            @Suppress("UNUSED_VARIABLE")
            var enriched = plainApiUserMessage
            try {
                @Suppress("UNUSED_VARIABLE")
                val memory = characterMemoryService.getMemoryContext(assistantId, sessionId, plainApiUserMessage)
                // enrichment logic is in ChatSessionActivity; for ViewModel we just pass through
                // the memory result is currently handled inline in Activity
            } catch (e: Exception) {
                Log.w(TAG, "memory-context failed: " + (e.message ?: ""))
            }
            // Post back to caller (Activity drives the actual dispatch after memory fetch)
            // For now, post a signal event through streamDeltaEvent with isMemoryReady=true
            // This is simplified: Activity's dispatchChatRequestWithOptionalMemory calls viewModel.doChatRequest
        }
    }

    /**
     * Direct chat dispatch (called from Activity after optional memory enrichment).
     */
    fun doChatRequest(
        historyForApi: List<Message>,
        apiUserMessage: String,
        options: SessionChatOptions,
        responseToken: Long,
        reportAssistantToMemory: Boolean,
        assistantId: String?,
        characterMemoryService: CharacterMemoryService
    ): ChatService.ChatHandle {
        val sid = sessionId ?: ""
        activeResponseToken = responseToken
        responseInProgress.postValue(true)
        val toolBridge = com.example.aichat.sync.ToolBridge.build(getApplication(), assistantId, sid)

        // 1. 角色对话 prepend 当前时间上下文 (周几 + 节气/节日). 时间是强相关的,
        //    早晨/深夜 / 周一/周末 / 立夏 等会改变角色的语气与状态.
        //    仅在绑定了非"writer"角色时注入, 避免污染纯创作场景.
        val timePrefix = buildTimeContextIfRoleplay(assistantId)

        // 2. 关系状态 (亲密度 / 信任 / 共同话题 / 情绪基调). 同上仅角色场景.
        val relationshipHint = buildRelationshipHintIfAny(assistantId)
        val closeness = readClosenessForAssistant(assistantId)

        // 3. 自动对话: 在 system 末尾注入 META 协议指令, 让模型在回复尾部自带 split / followUp 决策.
        //    closeness 影响 followUp 默认门槛 (亲密度高 → 主动消息阈值放宽).
        val autoChatSuffix = if (options.autoChatEnabled)
            ProactivePromptBuilder.buildSystemSuffix(closeness) else ""

        val mergedSystemPrompt = buildString {
            if (timePrefix.isNotEmpty()) append(timePrefix).append('\n')
            if (relationshipHint.isNotEmpty()) append(relationshipHint).append('\n')
            val origin = (options.systemPrompt ?: "").trim()
            if (origin.isNotEmpty()) append(origin)
            if (autoChatSuffix.isNotEmpty()) append('\n').append(autoChatSuffix)
        }
        val effectiveOptions = if (mergedSystemPrompt != options.systemPrompt)
            options.copy(systemPrompt = mergedSystemPrompt) else options

        val autoChatActive = options.autoChatEnabled

        val handle = chatService.chat(historyForApi, apiUserMessage, effectiveOptions,
            object : ChatService.ChatCallback {

                private fun isStale(): Boolean = responseToken != activeResponseToken

                /** 由 onProactiveMeta 写入, 之后 onSuccess 读取. 同线程顺序保证. */
                private var lastMeta: ProactiveMeta? = null

                override fun onProactiveMeta(meta: ProactiveMeta?) {
                    if (isStale()) return
                    lastMeta = meta
                }

                override fun onSuccess(content: String) {
                    if (isStale()) return
                    responseInProgress.postValue(false)
                    activeChatHandle = null
                    // content 已是 META-stripped cleanContent (ChatService 层完成抽取)
                    val safeContent = content
                    val capturedMeta = lastMeta
                    lastMeta = null

                    // Persist to DB as fallback for when Activity is detached/destroyed
                    // 自动对话路径: 这次 insert 的 row id 给 planner 用作 split rewrite 锚点.
                    executor.execute {
                        var insertedId: Long = 0L
                        try {
                            val msg = Message(sid, Message.ROLE_ASSISTANT, safeContent)
                            stampForSync(msg, assistantId)
                            insertedId = db.messageDao().insert(msg)
                        } catch (e: Exception) {
                            Log.w(TAG, "onSuccess insert failed", e)
                        }
                        if (autoChatActive && insertedId > 0) {
                            try {
                                ensurePlanner().onAssistantTurnFinalized(
                                    sid,
                                    assistantId ?: "",
                                    insertedId,
                                    safeContent,
                                    capturedMeta,
                                    options
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "planner.onAssistantTurnFinalized failed", e)
                            }
                        }
                    }
                    val event = StreamDeltaEvent(responseToken)
                    event.isSuccess = true
                    event.successContent = safeContent
                    event.reportAssistantToMemory = reportAssistantToMemory
                    event.autoChatActive = autoChatActive
                    postStreamEvent(event)
                }

                override fun onError(message: String) {
                    if (isStale()) return
                    responseInProgress.postValue(false)
                    activeChatHandle = null
                    errorEvent.postValue(
                        message.ifEmpty { getApplication<Application>().getString(R.string.error_request_failed) }
                    )
                    val event = StreamDeltaEvent(responseToken)
                    event.isError = true
                    postStreamEvent(event)
                }

                override fun onCancelled() {
                    if (isStale()) return
                    responseInProgress.postValue(false)
                    activeChatHandle = null
                    val event = StreamDeltaEvent(responseToken)
                    event.isCancelled = true
                    event.reportAssistantToMemory = reportAssistantToMemory
                    postStreamEvent(event)
                }

                override fun onPartial(delta: String) {
                    if (isStale()) return
                    val event = StreamDeltaEvent(responseToken)
                    event.delta = delta
                    postStreamEvent(event)
                }

                override fun onReasoning(reasoning: String) {
                    if (isStale()) return
                    val event = StreamDeltaEvent(responseToken)
                    event.reasoning = reasoning
                    postStreamEvent(event)
                }

                override fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int, elapsedMs: Long) {
                    if (isStale()) return
                    val event = StreamDeltaEvent(responseToken)
                    event.isUsage = true
                    event.promptTokens = promptTokens
                    event.completionTokens = completionTokens
                    event.totalTokens = totalTokens
                    event.elapsedMs = elapsedMs
                    postStreamEvent(event)
                }

                override fun onToolCallStart(toolName: String) {
                    if (isStale()) return
                    val event = StreamDeltaEvent(responseToken)
                    event.toolCallStarted = toolName
                    postStreamEvent(event)
                }

                override fun onToolMessageRecorded(record: ChatService.ToolMessageRecord) {
                    if (isStale()) return
                    // Persist tool-round messages to the local log for audit / replay.
                    // Intentionally bypass stampForSync: server schema doesn't yet
                    // accept role=tool_call/tool_result, so we leave turnId empty and
                    // SyncQueueDrainer's `WHERE turnId != ''` filter will skip it.
                    executor.execute {
                        try {
                            val msg = Message(sid, record.role, record.content)
                            msg.createdAt = record.createdAt
                            msg.toolCallsJson = record.toolCallsJson
                            msg.toolCallId = record.toolCallId
                            msg.toolName = record.toolName
                            db.messageDao().insert(msg)
                        } catch (e: Exception) {
                            Log.w(TAG, "persist tool message failed", e)
                        }
                    }
                }
            }, toolBridge)
        activeChatHandle = handle
        return handle
    }

    // ─────────────────────────── Thread Title ───────────────────────────

    fun generateThreadTitle(firstUserMessage: String?, fallbackTitle: String?) {
        chatService.generateThreadTitle(firstUserMessage, object : ChatService.ChatCallback {
            override fun onSuccess(content: String) {
                val generated = content.trim()
                if (generated.isEmpty()) return
                persistSessionTitle(generated, true)
                sessionTitle.postValue(generated)
            }

            override fun onError(message: String) {
                Log.e(TAG, "auto title failed: $message")
            }
        })
    }

    // ─────────────────────────── Response control ───────────────────────────

    fun incrementResponseToken(): Long {
        activeResponseToken++
        return activeResponseToken
    }

    fun getActiveResponseToken(): Long = activeResponseToken

    fun getActiveChatHandle(): ChatService.ChatHandle? = activeChatHandle

    fun clearActiveChatHandle() {
        activeChatHandle = null
    }

    fun isLoadingOlderMessages(): Boolean = loadingOlderMessages

    // ─────────────────────────── Lifecycle ───────────────────────────

    override fun onCleared() {
        try { planner?.shutdown() } catch (_: Exception) {}
        executor.shutdown()
        super.onCleared()
    }

    /**
     * 用户发送了新消息 → 取消任何 pending 的 follow-up timer.
     * Activity 在 sendMessage 路径调用; 即便 planner 没 init 也安全 (no-op).
     */
    fun cancelPendingProactive() {
        val sid = sessionId ?: return
        try { planner?.cancelFollowUp(sid) } catch (_: Exception) {}
    }

    /**
     * 构造"角色对话"开头的时间上下文行. 仅当 [assistantId] 绑定了非 "writer"
     * 类型的角色时返回非空; 写作 / 普通 chat 路径不注入.
     */
    private fun buildTimeContextIfRoleplay(assistantId: String?): String {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return ""
        val assistant = try { MyAssistantStore(getApplication()).getById(aid) }
            catch (_: Exception) { null } ?: return ""
        // 写作型助手 (writer) 不注入时间, 避免干扰创作语境.
        val type = (assistant.type ?: "").lowercase()
        if (type == "writer" || type == "novel" || type == "novelist") return ""
        return ChatTimeContext.describeNow()
    }

    /**
     * 取角色的关系状态 prompt hint (亲密度 / 信任 / 共同话题 / 情绪).
     * 没有缓存数据则返回空串.
     */
    private fun buildRelationshipHintIfAny(assistantId: String?): String {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return ""
        return try {
            RelationshipStateStore(getApplication()).buildPromptHintForAssistant(aid).orEmpty()
        } catch (_: Exception) { "" }
    }

    /**
     * 仅取 closeness 数值 (0-100). 用于 followUp 强度调制. 没有数据返回 null,
     * prompt builder 收到 null 时按默认行为输出 (不附加调制段).
     */
    private fun readClosenessForAssistant(assistantId: String?): Int? {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return null
        return try {
            val state = RelationshipStateStore(getApplication()).getCached(aid)
            if (state == null || state.closeness <= 0) null else state.closeness
        } catch (_: Exception) { null }
    }

    // ─────────────────────────── Stream Event Dispatch ───────────────────────────

    /**
     * Enqueue a streaming event and notify observers.
     *
     * LiveData.postValue() coalesces rapid calls — only the last pending value
     * is delivered, silently dropping intermediate content deltas ("吞字").
     * We buffer every event in [pendingStreamEvents] (never drops) and use
     * postValue purely as a wake-up signal.  The Activity calls
     * [drainPendingStreamEvents] inside its observer to retrieve all buffered events.
     */
    private fun postStreamEvent(event: StreamDeltaEvent) {
        pendingStreamEvents.add(event)
        streamDeltaEvent.postValue(event)
    }

    /**
     * Drain all buffered streaming events.  Called from the Activity's LiveData
     * observer on the main thread — guaranteed to return every event that was
     * enqueued since the last drain, regardless of LiveData coalescing.
     */
    fun drainPendingStreamEvents(): List<StreamDeltaEvent> {
        val result = mutableListOf<StreamDeltaEvent>()
        while (true) {
            val e = pendingStreamEvents.poll() ?: break
            result.add(e)
        }
        return result
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private fun toAscending(descList: List<Message>?): List<Message> {
        val out: MutableList<Message> = ArrayList()
        if (descList == null || descList.isEmpty()) return out
        out.addAll(descList)
        Collections.reverse(out)
        return out
    }

    // ─────────────────────────── StreamDeltaEvent ───────────────────────────

    /** Represents one streaming event from the AI response. */
    class StreamDeltaEvent(@JvmField val responseToken: Long) {
        // Content delta (onPartial)
        @JvmField var delta: String? = null
        // Reasoning delta (onReasoning)
        @JvmField var reasoning: String? = null
        // Usage stats (onUsage)
        @JvmField var isUsage: Boolean = false
        @JvmField var promptTokens: Int = 0
        @JvmField var completionTokens: Int = 0
        @JvmField var totalTokens: Int = 0
        @JvmField var elapsedMs: Long = 0
        // Terminal events
        @JvmField var isSuccess: Boolean = false
        @JvmField var successContent: String? = null
        @JvmField var reportAssistantToMemory: Boolean = false
        @JvmField var isError: Boolean = false
        @JvmField var isCancelled: Boolean = false
        // Tool call notification (onToolCallStart)
        @JvmField var toolCallStarted: String? = null
        /**
         * True when 自动对话 was on for this turn. Activity uses it to skip the
         * mass `persistSessionMessagesAsync` overwrite (planner owns DB writes
         * for proactive turns).
         */
        @JvmField var autoChatActive: Boolean = false
    }

    /**
     * Incremental message log update event from 自动对话 planner / follow-up worker.
     *
     * Two flavours:
     *  - REPLACE: an existing row (`rowId`) had its content rewritten (split[0]).
     *  - APPEND: a brand-new row was inserted (split[1..N] or follow-up).
     *
     * Activity观察后做 RecyclerView 局部刷新, 避免 loadMessages 全量重读.
     */
    class ProactiveMessageEvent private constructor(
        @JvmField val kind: Int,
        @JvmField val rowId: Long,
        @JvmField val newContent: String,
        @JvmField val appendedMessage: Message?,
    ) {
        companion object {
            const val KIND_REPLACE = 1
            const val KIND_APPEND = 2

            @JvmStatic
            fun replace(rowId: Long, newContent: String): ProactiveMessageEvent =
                ProactiveMessageEvent(KIND_REPLACE, rowId, newContent, null)

            @JvmStatic
            fun append(msg: Message): ProactiveMessageEvent =
                ProactiveMessageEvent(KIND_APPEND, msg.id, msg.content, msg)
        }
    }
}
