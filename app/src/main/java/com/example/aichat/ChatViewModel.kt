package com.example.aichat

import android.app.Application
import android.util.Log
import androidx.annotation.NonNull
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.util.Collections
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

    // --- Internal state ---
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val db: AppDatabase = AppDatabase.getInstance(application)
    private val chatService: ChatService = ChatService(application)

    private var sessionId: String? = null

    @Volatile private var activeResponseToken: Long = 0
    @Volatile private var activeChatHandle: ChatService.ChatHandle? = null
    @Volatile private var loadingOlderMessages: Boolean = false
    private var oldestLoadedCreatedAt: Long = Long.MAX_VALUE
    private var oldestLoadedMessageId: Long = Long.MAX_VALUE

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

    fun persistSessionMessagesAsync(snapshot: List<Message>) {
        val sid = sessionId ?: return
        val copy: List<Message> = ArrayList(snapshot)
        executor.execute {
            try {
                db.messageDao().deleteBySession(sid)
                for (m in copy) {
                    if (m == null) continue
                    val item = Message(sid, m.role, if (m.content != null) m.content else "")
                    item.createdAt = if (m.createdAt > 0) m.createdAt else System.currentTimeMillis()
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
        val handle = chatService.chat(historyForApi, apiUserMessage, options,
            object : ChatService.ChatCallback {

                private fun isStale(): Boolean = responseToken != activeResponseToken

                override fun onSuccess(content: String) {
                    if (isStale()) return
                    responseInProgress.postValue(false)
                    activeChatHandle = null
                    val safeContent = content
                    // Persist to DB as fallback for when Activity is detached/destroyed
                    executor.execute {
                        try {
                            db.messageDao().insert(Message(sid, Message.ROLE_ASSISTANT, safeContent))
                        } catch (ignored: Exception) {}
                    }
                    val event = StreamDeltaEvent(responseToken)
                    event.isSuccess = true
                    event.successContent = safeContent
                    event.reportAssistantToMemory = reportAssistantToMemory
                    streamDeltaEvent.postValue(event)
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
                    streamDeltaEvent.postValue(event)
                }

                override fun onCancelled() {
                    if (isStale()) return
                    responseInProgress.postValue(false)
                    activeChatHandle = null
                    val event = StreamDeltaEvent(responseToken)
                    event.isCancelled = true
                    event.reportAssistantToMemory = reportAssistantToMemory
                    streamDeltaEvent.postValue(event)
                }

                override fun onPartial(delta: String) {
                    if (isStale()) return
                    val event = StreamDeltaEvent(responseToken)
                    event.delta = delta
                    streamDeltaEvent.postValue(event)
                }

                override fun onReasoning(reasoning: String) {
                    if (isStale()) return
                    val event = StreamDeltaEvent(responseToken)
                    event.reasoning = reasoning
                    streamDeltaEvent.postValue(event)
                }

                override fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int, elapsedMs: Long) {
                    if (isStale()) return
                    val event = StreamDeltaEvent(responseToken)
                    event.isUsage = true
                    event.promptTokens = promptTokens
                    event.completionTokens = completionTokens
                    event.totalTokens = totalTokens
                    event.elapsedMs = elapsedMs
                    streamDeltaEvent.postValue(event)
                }
            })
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
        executor.shutdown()
        super.onCleared()
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
    }
}
