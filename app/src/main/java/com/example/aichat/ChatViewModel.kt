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
        // 微信式分页：默认只渲染最近 60 条，上拉加载更多。降低长会话打开时的卡顿。
        private const val INITIAL_RENDER_MESSAGE_LIMIT = 60
        private const val LOAD_MORE_BATCH_SIZE = 30
        private const val MAX_CORE_MEMORIES_IN_PROMPT = 8
        private const val MAX_CORE_FACTS_IN_PROMPT = 15
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

    /**
     * 与 pendingStreamEvents 同理: proactiveMessageEvent 也用队列保护.
     * Activity 处于 STOPPED 状态时 LiveData 只保留最后一次 postValue, 多段 split
     * 如果在后台依次触发, 只有最后一段到达 UI — 前面的段永久丢失.
     * 解法: 事件先入队, postValue 只作"有新事件"的通知信号, Activity 每次收到
     * 通知后把整个队列 drain 出来按顺序处理.
     */
    private val pendingProactiveEvents = ConcurrentLinkedQueue<ProactiveMessageEvent>()

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
                    val evt = ProactiveMessageEvent.replace(rowId, newContent)
                    pendingProactiveEvents.offer(evt)
                    proactiveMessageEvent.postValue(evt) // 仅作通知信号; Activity drain 队列获取内容
                },
                onMessageAppended = { msg ->
                    val evt = ProactiveMessageEvent.append(msg)
                    pendingProactiveEvents.offer(evt)
                    proactiveMessageEvent.postValue(evt)
                }
            ).also { planner = it }
        }
    }

    /** Activity observer 调用此方法排空队列，确保所有事件都被处理，不因 LiveData coalesce 而丢失. */
    fun drainPendingProactiveEvents(): List<ProactiveMessageEvent> {
        val out = mutableListOf<ProactiveMessageEvent>()
        while (true) out.add(pendingProactiveEvents.poll() ?: break)
        return out
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
                val newId = db.messageDao().insert(message)
                if (newId > 0) message.id = newId
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Insert a message and stamp it for remote sync. 普通会话 (assistantId 空) 用
     * `DefaultAssistantId(modelKey + 季度)` 生成 fallback assistantId, 同样进入同步队列.
     * 仅 user/assistant 普通消息进队列; tool_call / tool_result / proactiveKind != 0 行跳过.
     *
     * insert 返回的 row id 同步回写到 [message].id, 让后续 persistSessionMessagesAsync
     * 等增量路径可以按 id upsert, 不再丢失行身份.
     */
    fun insertMessageAsync(message: Message, assistantId: String?) {
        executor.execute {
            try {
                stampForSync(message, assistantId)
                val newId = db.messageDao().insert(message)
                if (newId > 0) message.id = newId
                relayInsertToWs(message)
            } catch (ignored: Exception) {}
        }
    }

    private fun stampForSync(message: Message, assistantId: String?) {
        if (message.role != Message.ROLE_USER && message.role != Message.ROLE_ASSISTANT) return
        if (message.proactiveKind != 0) return

        val explicit = assistantId?.trim().orEmpty()
        val finalAssistantId = if (explicit.isNotEmpty()) {
            explicit
        } else {
            val modelKey = SessionChatOptionsStore(getApplication()).get(message.sessionId).modelKey
            com.example.aichat.sync.DefaultAssistantId.forModelKey(modelKey, message.createdAt)
        }
        if (message.turnId.isEmpty()) message.turnId = com.example.aichat.sync.UuidV7.next()
        if (message.assistantId.isEmpty()) message.assistantId = finalAssistantId
        message.synced = 0
    }

    /**
     * 实时通道: ws 在线时把刚 insert 的 turn 推到 server, 让 server 端尽快感知用户输入
     * (next_push 触发链路依赖这条). server 应答 message_persisted 会异步把 synced 标 1,
     * drainer 自然不会再 sync_push. ws 离线/失败时 no-op, drainer 兜底.
     */
    private fun relayInsertToWs(message: Message) {
        if (message.turnId.isEmpty() || message.assistantId.isEmpty()) return
        if (message.role != Message.ROLE_USER && message.role != Message.ROLE_ASSISTANT) return
        if (message.proactiveKind != 0) return
        val role = if (message.role == Message.ROLE_USER) "user" else "assistant"
        com.example.aichat.sync.WsClient.sendMessageCreate(
            turnId = message.turnId,
            assistantId = message.assistantId,
            sessionId = message.sessionId,
            role = role,
            content = message.content ?: "",
            createdAt = if (message.createdAt > 0) message.createdAt else System.currentTimeMillis(),
        )
    }

    /** 实时通道: content 改了 → 让 server re-embed memory. ws 离线时 no-op. */
    private fun relayUpdateToWs(turnId: String, content: String, assistantId: String) {
        if (turnId.isEmpty()) return
        com.example.aichat.sync.WsClient.sendMessageUpdate(turnId, content, assistantId)
    }

    /** 实时通道: message 被删 → 通知 server 删 turn + memory embedding + 跨端同步. */
    private fun relayDeleteToWs(turnId: String, assistantId: String) {
        if (turnId.isEmpty()) return
        com.example.aichat.sync.WsClient.sendMessageDelete(turnId, assistantId)
    }

    /**
     * 删一条消息: DB 删 + WS 同步 (server re-embed/删 memory + 跨端).
     *
     * Split 消息的处理:
     *   - split[0..N] 全部共享同一个 turnId (splitGroupTurnId), proactiveKind=1.
     *   - 删除其中任意一段时, 通过 turnId 一次性清掉整组, 不留孤儿行.
     *   - WS delete 只发一次 (用共享 turnId), server 只认识 split[0] 那条,
     *     split[1..N] 的 synced=1 从未推送, 不会产生无效 404.
     *
     * 不依赖 persistSessionMessagesAsync 的对账 — proactive 行 (远程推送 / 仿推送 / split)
     * 不在对账列表里, 仅靠 allMessages 内存移除是删不掉 DB 的, 重启 / loadMessages 又会被
     * 读出来 → "删除失败". onDelete 单条删除场景应直接调这个.
     */
    fun deleteMessageAsync(message: Message) {
        if (message.id <= 0L) return
        val msgId = message.id
        val turnId = message.turnId
        val assistantId = message.assistantId
        val isSplitGroup = message.proactiveKind == 1 && turnId.isNotEmpty()
        executor.execute {
            try {
                if (isSplitGroup) {
                    // 整组删除: 清掉所有共享同一 turnId 的 proactive 段落 (split[0..N]).
                    db.messageDao().deleteSplitGroupByTurnId(turnId)
                } else {
                    db.messageDao().deleteById(msgId)
                }
            } catch (ignored: Exception) {}
            // ws 离线时 no-op; server 没收到也无大碍 — 本地 DB 已经删, 同账号其它端
            // 下次拉 server 也拉不到这条 (server 删了) 或仍能拉到 (server 没删, 此时
            // 用户在其它端再删一次即可).
            // split 组只发一次 WS delete (turnId 唯一), server 只认识 split[0] 那条.
            relayDeleteToWs(turnId, assistantId)
        }
    }

    /**
     * 增量同步 Activity 的 in-memory message list 到 DB:
     *   - id > 0 且 DB 里在 → update content/reasoning, 保留 turnId/synced
     *   - id == 0 → insert + stampForSync, 写回 m.id
     *   - DB 里有但 snapshot 没有的 row → deleteById
     *
     * 仅作用于 user/assistant 普通消息 (role 0/1, proactiveKind=0). tool 行 / split /
     * follow-up 行不在 snapshot 里, 也不在这里清理.
     *
     * 早期版本是 deleteUserAssistantBySession + reinsert 的 snapshot replace 模式,
     * 会擦掉每行的 turnId/synced/syncAttempts 并漂移 row id, 导致同一条消息在 server
     * 端重复出现. 现已改为按 id 的增量 upsert.
     */
    fun persistSessionMessagesAsync(snapshot: List<Message>) {
        val sid = sessionId ?: return
        val copy: List<Message> = ArrayList(snapshot)
        executor.execute {
            try {
                val dao = db.messageDao()
                val existing = dao.getUserAssistantBySession(sid)
                val existingById: Map<Long, Message> = existing.associateBy { it.id }
                val keptIds = HashSet<Long>()

                for (m in copy) {
                    if (m == null) continue
                    if (m.role != Message.ROLE_USER && m.role != Message.ROLE_ASSISTANT) continue
                    if (m.proactiveKind != 0) continue
                    val matched = if (m.id > 0L) existingById[m.id] else null
                    if (matched != null) {
                        // 已在 DB → 仅更新内容字段, 保留 sync 状态
                        keptIds.add(matched.id)
                        val newContent = m.content ?: ""
                        val newReasoning = m.reasoning ?: ""
                        if (matched.content != newContent
                            || matched.reasoning != newReasoning
                            || matched.thinkingElapsedMs != m.thinkingElapsedMs
                        ) {
                            dao.updateContentForSnapshot(
                                matched.id, newContent, newReasoning, m.thinkingElapsedMs
                            )
                            // content 改了 → ws 同步推 server 重 embed memory
                            if (matched.content != newContent) {
                                relayUpdateToWs(matched.turnId, newContent, matched.assistantId)
                            }
                        }
                    } else {
                        // 新行 → insert + stampForSync, id 回写到 m
                        if (m.createdAt <= 0L) m.createdAt = System.currentTimeMillis()
                        if (m.sessionId.isEmpty()) m.sessionId = sid
                        // m.assistantId 可能空; stampForSync 会按 SessionChatOptions.modelKey
                        // 走 fallback (default-{provider}-{quarter}).
                        stampForSync(m, m.assistantId.takeIf { it.isNotEmpty() })
                        val newId = dao.insert(m)
                        if (newId > 0) {
                            m.id = newId
                            keptIds.add(newId)
                        }
                        relayInsertToWs(m)
                    }
                }
                // snapshot 没保留的旧行 → 删除 (来自 onDelete / onRegenerate 砍尾)
                for (e in existing) {
                    if (e.id !in keptIds) dao.deleteById(e.id)
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
     * Chat dispatch entry. Pass [chatCtx] (V3 `POST /api/chat/context` response)
     * when remote sync is enabled — system prompt is then assembled from
     * server-rendered `mergedSystem` + a client-only `<client>` slot
     * (path B in wi-chat-server/docs/client-prompt-merge-protocol.md).
     *
     * If [chatCtx] is null we fall back to the boot-cache `mergedSystem` from
     * `POST /api/character/context` and the local-only hints — same behavior
     * as before V3 wired up.
     */
    fun doChatRequest(
        historyForApi: List<Message>,
        apiUserMessage: String,
        options: SessionChatOptions,
        responseToken: Long,
        assistantId: String?,
        chatCtx: com.example.aichat.sync.ChatContextResponse?,
    ): ChatService.ChatHandle {
        val sid = sessionId ?: ""
        activeResponseToken = responseToken
        responseInProgress.postValue(true)
        val toolBridge = com.example.aichat.sync.ToolBridge.build(getApplication(), assistantId, sid)

        // ── Client-side dynamic hints — go into the `<client>` slot in V3 path B,
        //    or get prepended/appended around boot cache mergedSystem in fallback.
        val timePrefix = buildTimeContextIfRoleplay(assistantId)
        val relationshipHint = buildRelationshipHintIfAny(assistantId)
        val closeness = readClosenessForAssistant(assistantId)
        // TODO(V3 ablation): 工具使用指引暂时关闭，验证 V3 router 在没有 client tool
        // 提示时的效果。要恢复就把下面两行换回 `buildToolSystemHint(toolBridge)`。
        // val toolSystemHint = buildToolSystemHint(toolBridge)
        val toolSystemHint = ""
        // V6 自动对话 JSON mode 只在 "角色人物" 类型 assistant 上启用. 写作 / 普通
        // chat / novelist 类型即便用户开了 autoChat 也不走 JSON 协议 — 否则会污染
        // 创作 / 通用对话场景的输出格式.
        val isCharacter = isCharacterAssistant(assistantId)
        val effectiveAutoChat = options.autoChatEnabled && isCharacter
        Log.d(TAG, "autoChat: autoChatEnabled=${options.autoChatEnabled} isCharacter=$isCharacter → effectiveAutoChat=$effectiveAutoChat | aid=$assistantId")
        val autoChatSuffix = if (effectiveAutoChat)
            ProactivePromptBuilder.buildSystemSuffix(closeness) else ""
        val localSystemPrompt = (options.systemPrompt ?: "").trim()

        val promptSource: String
        val mergedSystemPrompt = if (chatCtx != null) {
            promptSource = "v3"
            composeSystemPromptV3(
                chatCtx,
                timePrefix = timePrefix,
                relationshipHint = relationshipHint,
                localSystemPrompt = localSystemPrompt,
                toolSystemHint = toolSystemHint,
                autoChatSuffix = autoChatSuffix,
            )
        } else {
            promptSource = "fallback"
            composeSystemPromptFallback(
                assistantId = assistantId,
                timePrefix = timePrefix,
                relationshipHint = relationshipHint,
                localSystemPrompt = localSystemPrompt,
                toolSystemHint = toolSystemHint,
                autoChatSuffix = autoChatSuffix,
            )
        }
        // Always copy: autoChatEnabled 可能被 isCharacter 收紧, 必须把 effective 值
        // 传给 ChatService — 它要据此决定是否注入 response_format=json_object 和屏蔽
        // streaming onPartial.
        val effectiveOptions = options.copy(
            systemPrompt = mergedSystemPrompt,
            autoChatEnabled = effectiveAutoChat,
        )

        // Snapshot 给 CharacterInfoActivity 读，让"查看角色信息"页面能展示真实下发的 prompt。
        recordEffectivePrompt(assistantId, mergedSystemPrompt, promptSource, chatCtx)

        val autoChatActive = effectiveAutoChat

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

                    // Persist to DB on application-scoped executor — this MUST run
                    // even if ViewModel.onCleared has already fired (user finished
                    // the chat page mid-stream). See [IngestExecutor].
                    val event = StreamDeltaEvent(responseToken)
                    event.isSuccess = true
                    event.successContent = safeContent
                    event.autoChatActive = autoChatActive
                    IngestExecutor.execute {
                        var insertedId: Long = 0L
                        val msg = Message(sid, Message.ROLE_ASSISTANT, safeContent)
                        try {
                            stampForSync(msg, assistantId)
                            insertedId = db.messageDao().insert(msg)
                            if (insertedId > 0) msg.id = insertedId
                            relayInsertToWs(msg)
                        } catch (e: Exception) {
                            Log.w(TAG, "onSuccess insert failed", e)
                        }
                        event.assistantInsertedId = insertedId
                        event.assistantTurnId = msg.turnId
                        event.assistantAssignedId = msg.assistantId
                        postStreamEvent(event)
                        Log.d(TAG, "onSuccess: autoChatActive=$autoChatActive insertedId=$insertedId meta=${capturedMeta?.let { "split=${it.split?.size} followUp=${it.followUp?.afterSec} autoStop=${it.autoStop}" } ?: "null"}")
                        if (autoChatActive && insertedId > 0) {
                            try {
                                ensurePlanner().onAssistantTurnFinalized(
                                    sid,
                                    assistantId ?: "",
                                    insertedId,
                                    msg.turnId,
                                    safeContent,
                                    capturedMeta,
                                    options
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "planner.onAssistantTurnFinalized failed", e)
                            }
                        }
                    }
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
                    // App-scoped executor: tool-round messages may arrive after the
                    // user has left the chat page (Activity finished). See [IngestExecutor].
                    IngestExecutor.execute {
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
     * 用户发送了新消息 → 取消任何 pending 的 follow-up timer 和 split runnables.
     * Activity 在 sendMessage 路径调用; 即便 planner 没 init 也安全 (no-op).
     *
     * 之前只取消了 followUp, split runnables 被遗漏 — 会导致旧轮次的分段消息在
     * 新消息回复下面冒出来. 现在一并取消.
     */
    fun cancelPendingProactive() {
        val sid = sessionId ?: return
        try { planner?.cancelFollowUp(sid) } catch (_: Exception) {}
        try { planner?.cancelPendingSplits(sid) } catch (_: Exception) {}
    }

    /**
     * 严格判定 assistant 是否是 "角色人物" 类型 (type == "character"). V6 自动对话
     * JSON mode 只对这类 assistant 启用, 写作 / 普通 chat / novelist 都返回 false.
     */
    private fun isCharacterAssistant(assistantId: String?): Boolean {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return false
        return try {
            val a = MyAssistantStore(getApplication()).getById(aid) ?: return false
            "character" == (a.type ?: "").lowercase()
        } catch (_: Exception) { false }
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

    private fun recordEffectivePrompt(
        assistantId: String?,
        systemPrompt: String,
        source: String,
        chatCtx: com.example.aichat.sync.ChatContextResponse?,
    ) {
        val routerSummary = chatCtx?.routerDecision?.let { rd ->
            "register=${rd.register ?: "-"}  skills=[${rd.skillIds?.joinToString(",") ?: ""}]  budget=${rd.budget ?: "-"}"
        }
        com.example.aichat.sync.EffectivePromptStore.record(
            assistantId,
            com.example.aichat.sync.EffectivePromptStore.Snapshot(
                systemPrompt = systemPrompt,
                source = source,
                capturedAtMs = System.currentTimeMillis(),
                routerSummary = routerSummary,
            )
        )
    }

    /**
     * V3 path A+：直接用 server 渲染好的 [mergedSystem] 当主 system prompt。
     *
     * 拼装顺序（最末尾 = 最强 recency bias）：
     *   mergedSystem  ← server 给的，含 role / style / voice_skills / ... / avoid
     *   <client>...   ← 客户端语境（本地时间 / relationship / localSystemPrompt）
     *   <output_protocol>...  ← 自动对话 META 协议（如果 autoChatEnabled）
     *   prefill       ← 角色独白片段（V3 默认空字符串）
     *
     * `<output_protocol>` 必须在最末尾才能压住 split 决策——之前测试发现夹在中间会
     * 被前面的 `<avoid>` 段拉跑（"避免枚举式回答" → 误读为"不要 split"）。server 的
     * `<avoid>` 已改成"避免 1.2.3 编号列表"避免歧义，但 protocol 仍放末尾保险。
     */
    private fun composeSystemPromptV3(
        chatCtx: com.example.aichat.sync.ChatContextResponse,
        timePrefix: String,
        relationshipHint: String,
        localSystemPrompt: String,
        toolSystemHint: String,
        autoChatSuffix: String,
    ): String {
        val merged = chatCtx.mergedSystem?.trim().orEmpty()
        val clientSlot = buildClientSlot(
            timePrefix, relationshipHint, localSystemPrompt, toolSystemHint
        )
        val outputProtocol = if (autoChatSuffix.isNotEmpty()) {
            buildString {
                append("<output_protocol>\n")
                append(autoChatSuffix.trim())
                append("\n</output_protocol>")
            }
        } else ""
        val prefill = chatCtx.assistantPrefill?.trim().orEmpty()

        return buildString {
            if (merged.isNotEmpty()) append(merged)
            appendBlock(clientSlot)
            appendBlock(outputProtocol)
            appendBlock(prefill)
        }
    }

    private fun StringBuilder.appendBlock(block: String) {
        if (block.isEmpty()) return
        if (this.isNotEmpty()) append("\n\n")
        append(block)
    }

    /** Render a `<client>` XML slot from local-only signals. Empty if all empty. */
    private fun buildClientSlot(
        timePrefix: String,
        relationshipHint: String,
        localSystemPrompt: String,
        toolSystemHint: String,
    ): String {
        val parts = listOfNotNull(
            timePrefix.takeIf { it.isNotEmpty() },
            relationshipHint.takeIf { it.isNotEmpty() },
            localSystemPrompt.takeIf { it.isNotEmpty() },
            toolSystemHint.takeIf { it.isNotEmpty() },
        )
        if (parts.isEmpty()) return ""
        return buildString {
            append("<client>\n")
            append(parts.joinToString("\n\n"))
            append("\n</client>")
        }
    }

    /**
     * Fallback when chat/context isn't available: same legacy layout we used
     * before V3 — local hints around the boot-cache mergedSystem (which has
     * facts/narrative slots empty, but role/character/etc are populated).
     */
    private fun composeSystemPromptFallback(
        assistantId: String?,
        timePrefix: String,
        relationshipHint: String,
        localSystemPrompt: String,
        toolSystemHint: String,
        autoChatSuffix: String,
    ): String {
        val bootstrapPrefix = buildBootstrapPrefixIfAny(assistantId)
        val outputProtocol = if (autoChatSuffix.isNotEmpty()) {
            buildString {
                append("<output_protocol>\n")
                append(autoChatSuffix.trim())
                append("\n</output_protocol>")
            }
        } else ""
        return buildString {
            if (timePrefix.isNotEmpty()) append(timePrefix).append('\n')
            if (relationshipHint.isNotEmpty()) append(relationshipHint).append('\n')
            if (bootstrapPrefix.isNotEmpty()) append(bootstrapPrefix).append('\n')
            if (localSystemPrompt.isNotEmpty()) append(localSystemPrompt)
            if (toolSystemHint.isNotEmpty()) append("\n\n").append(toolSystemHint)
            if (outputProtocol.isNotEmpty()) append("\n\n").append(outputProtocol)
        }
    }

    /**
     * Fallback system prompt prefix built from boot-cache [CharacterBootstrapStore].
     * Uses [ChatRenderedSlots] (role / character / background / constraints) rather
     * than the pre-merged monolithic string, so we control which slots go in and in
     * what order. toolProtocol is omitted — per-turn tool hints come from chatCtx.
     */
    private fun buildBootstrapPrefixIfAny(assistantId: String?): String {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return ""
        val cache = try {
            com.example.aichat.sync.CharacterBootstrapStore.getInstance(getApplication())
                .getCached(aid)
        } catch (_: Exception) { null } ?: return ""

        val slots = cache.renderedSlots
        if (slots != null) {
            val parts = listOfNotNull(
                slots.role?.trim()?.takeIf { it.isNotEmpty() },
                slots.character?.trim()?.takeIf { it.isNotEmpty() },
                slots.background?.trim()?.takeIf { it.isNotEmpty() },
                slots.constraints?.trim()?.takeIf { it.isNotEmpty() },
            )
            if (parts.isNotEmpty()) return parts.joinToString("\n\n")
        }
        // slots 未缓存时（旧 boot cache 未包含 renderedSlots）退到本地 systemPrompt，
        // 什么都不注入，让 composeSystemPromptFallback 只拼客户端信号。
        return ""
    }

    /**
     * 模型级工具使用指引. 和角色人设完全分离 — 这段写给"AI 模型"而非"角色".
     * 只在 ToolBridge ready 时返回内容, 否则空串.
     */
    private fun buildToolSystemHint(toolBridge: com.example.aichat.sync.ToolBridge?): String {
        if (toolBridge == null || !toolBridge.isReady()) return ""
        val toolNames = try {
            toolBridge.toolsJson().mapNotNull { el ->
                el.asJsonObject?.getAsJsonObject("function")?.get("name")?.asString
            }
        } catch (_: Exception) { emptyList() }
        if (toolNames.isEmpty()) return ""

        return buildString {
            append("[System — Tool Instructions]\n")
            append("You have ${toolNames.size} tool(s) available: ${toolNames.joinToString(", ")}.\n")
            if ("search_memory" in toolNames) {
                append("- search_memory: search the user's conversation history and character narratives. ")
                append("Use it when the user references past events, preferences, plans, or relationships. ")
                append("For most queries about what the user said or experienced, use source='user' (default) or omit. ")
                append("source='character' only searches character-generated internal narratives (very few entries), NOT user conversations. ")
                append("Use source='all' when unsure.\n")
            }
            if ("correct_memory" in toolNames) {
                append("- correct_memory: fix or delete incorrect memories found via search_memory.\n")
            }
            append("Call tools when relevant; do not fabricate information you could look up. ")
            append("If a search returns count=0, tell the user honestly that no record was found.")
        }
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
        /**
         * onSuccess 时 ViewModel insert 的 assistant row 的真实 id / sync 字段.
         * Activity 用它把 streaming 对象同步到 DB 的真实状态, 让后续 persist
         * 增量路径可以按 id upsert, 不再丢 turnId / 漂移 id.
         */
        @JvmField var assistantInsertedId: Long = 0L
        @JvmField var assistantTurnId: String = ""
        @JvmField var assistantAssignedId: String = ""
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
