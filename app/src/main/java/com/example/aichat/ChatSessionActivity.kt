package com.example.aichat

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.MotionEvent
import android.view.ViewParent
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.core.widget.NestedScrollView
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.ArrayList
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider

class ChatSessionActivity : ThemedActivity() {

    companion object {
        private const val TAG = "ChatSessionActivity"
        private const val PREFS_CHAPTER_PLAN = "chapter_plan_prefs"
        private const val KEY_LAST_TARGET_LENGTH = "last_target_length"
        private const val DEFAULT_TARGET_LENGTH = "3000"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_INITIAL_MESSAGE = "initial_message"
        const val EXTRA_ASSISTANT_ID = "assistant_id"
        private const val STREAM_RENDER_THROTTLE_MS = 24L
        private const val STREAM_RENDER_THROTTLE_BUSY_MS = 48L
        private const val STREAM_RENDER_BUSY_PENDING_CHARS = 80
        private const val STREAM_TYPEWRITER_FRAME_MS = 16L
        private const val STREAM_TYPEWRITER_CHARS_PER_FRAME = 4
        private const val STREAM_AUTO_SCROLL_THROTTLE_MS = 300L
        private const val AUTO_SCROLL_BOTTOM_GAP_DP = 32
        private const val WRITER_ASSISTANT_CONTEXT_EXCERPT_MAX_CHARS = 500
        private const val WRITER_ASSISTANT_LAST_SEGMENT_CHARS = 1000
        private const val CHARACTER_MEMORY_LOADING_TEXT = "[...正在输入中]"
        private const val INITIAL_RENDER_MESSAGE_LIMIT = 200
        private const val LOAD_MORE_BATCH_SIZE = 50
        private const val TOP_LOAD_TRIGGER_GAP_DP = 8
        private const val PROACTIVE_POLL_INTERVAL_MS = 30_000L
    }

    private var sessionId: String = ""
    private lateinit var historyAdapter: MessageAdapter
    private lateinit var currentAdapter: MessageAdapter
    private val assistantMarkdownStateStore = MessageAdapter.AssistantMarkdownStateStore()
    private var sendButtonView: MaterialButton? = null
    private var inputEditView: EditText? = null
    private lateinit var chatService: ChatService
    private lateinit var viewModel: ChatViewModel
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeThinkingMessage: Message? = null

    private val thinkingTicker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val msg = activeThinkingMessage ?: return
            if (!msg.thinkingRunning) return
            msg.thinkingElapsedMs = maxOf(0L, System.currentTimeMillis() - msg.thinkingStartedAt)
            renderStreamingMessageTick(msg)
            mainHandler.postDelayed(this, 500L)
        }
    }

    private var historyExpanded = false
    private var addActionsExpanded = false
    private var allMessages: MutableList<Message> = ArrayList()
    private var scrollMessagesView: NestedScrollView? = null
    private var autoScrollToBottomEnabled = true
    private var pendingInitialMessage: String? = null
    private var assistantId: String? = null
    private var writerAssistant = false
    private var characterAssistant = false
    private var characterMemoryService: CharacterMemoryService? = null
    private var outlineStore: SessionOutlineStore? = null
    private var proactiveSyncManager: ProactiveMessageSyncManager? = null
    private var sessionOptions: SessionChatOptions = SessionChatOptions()
    @Volatile private var autoNamingInFlight = false
    private var assistantResponseInProgress = false
    private var streamRenderPending = false
    private var lastStreamRenderAt = 0L
    private var activeChatHandle: ChatService.ChatHandle? = null
    private var activeStreamingMessage: Message? = null
    private var activeResponseToken = 0L
    private var lastStreamAutoScrollAt = 0L
    private var streamingTargetMessage: Message? = null
    private val pendingStreamChars = StringBuilder()
    private var streamTypewriterRunning = false
    private var characterMemoryLoadingMessage: Message? = null
    private var loadEarlierMessagesView: TextView? = null
    private var quickModelSwitchView: TextView? = null
    private var firstDialoguePreviewView: TextView? = null
    private var expandHistoryView: View? = null
    private var historyExpandIconView: View? = null
    private var hasMoreOlderMessages = false
    private var loadingOlderMessages = false
    private var olderRemainingCount = 0
    private var oldestLoadedCreatedAt = Long.MAX_VALUE
    private var oldestLoadedMessageId = Long.MAX_VALUE
    private var proactivePollingActive = false

    private val proactivePollRunnable = object : Runnable {
        override fun run() {
            if (!proactivePollingActive || isFinishing || isDestroyed) return
            proactiveSyncManager?.syncOnce(object : ProactiveMessageSyncManager.SyncCallback {
                override fun onSessionUpdated(updatedSessionId: String) {
                    if (updatedSessionId != sessionId) return
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        loadMessages()
                    }
                }
            })
            mainHandler.postDelayed(this, PROACTIVE_POLL_INTERVAL_MS)
        }
    }

    private val streamRenderRunnable = Runnable {
        streamRenderPending = false
        lastStreamRenderAt = System.currentTimeMillis()
        renderStreamingMessageTick(streamingTargetMessage)
    }

    private val streamTypewriterRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) {
                streamTypewriterRunning = false
                return
            }
            if (streamingTargetMessage == null) {
                streamTypewriterRunning = false
                pendingStreamChars.setLength(0)
                return
            }
            if (pendingStreamChars.isEmpty()) {
                streamTypewriterRunning = false
                return
            }
            val take = minOf(STREAM_TYPEWRITER_CHARS_PER_FRAME, pendingStreamChars.length)
            val delta = pendingStreamChars.substring(0, take)
            pendingStreamChars.delete(0, take)
            val targetMsg = streamingTargetMessage
            val old = targetMsg?.content ?: ""
            targetMsg?.content = old + delta
            var rendered = historyAdapter.renderStreamingMessageIfVisible(targetMsg)
            rendered = rendered or currentAdapter.renderStreamingMessageIfVisible(targetMsg)
            if (!rendered) {
                scheduleStreamRender()
            } else {
                maybeAutoScrollOnStreamTick()
            }
            if (pendingStreamChars.isNotEmpty()) {
                mainHandler.postDelayed(this, STREAM_TYPEWRITER_FRAME_MS)
            } else {
                streamTypewriterRunning = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_session)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
        pendingInitialMessage = intent.getStringExtra(EXTRA_INITIAL_MESSAGE)
        if (pendingInitialMessage != null) {
            intent.removeExtra(EXTRA_INITIAL_MESSAGE)
        }
        assistantId = intent.getStringExtra(EXTRA_ASSISTANT_ID)
        if (!assistantId.isNullOrEmpty()) {
            SessionAssistantBindingStore(this).bind(sessionId, assistantId!!)
        } else {
            assistantId = SessionAssistantBindingStore(this).getAssistantId(sessionId)
        }
        writerAssistant = resolveWriterAssistant()
        characterAssistant = resolveCharacterAssistant()
        outlineStore = SessionOutlineStore(this)
        characterMemoryService = CharacterMemoryService(this)
        proactiveSyncManager = ProactiveMessageSyncManager(this)

        chatService = ChatService(this)
        viewModel = ViewModelProvider(this).get(ChatViewModel::class.java)

        // --- Observe ViewModel LiveData ---
        viewModel.messages.observe(this) { msgs ->
            if (isFinishing || isDestroyed) return@observe
            allMessages = ArrayList(msgs)
            maybeInsertAssistantOpeningMessage()
            val pending = pendingInitialMessage
            if (!pending.isNullOrEmpty()) {
                pendingInitialMessage = null
                val input: EditText? = findViewById(R.id.inputEdit)
                if (input != null) {
                    input.post { sendMessageFromText(pending) }
                } else {
                    sendMessageFromText(pending)
                }
                return@observe
            }
            applyMessagesAndTitle()
            maybeAutoScrollToBottom(true)
            updateLoadEarlierEntryVisibility()
        }
        viewModel.hasMoreOlderMessages.observe(this) { has ->
            if (isFinishing || isDestroyed) return@observe
            hasMoreOlderMessages = has != null && has
            updateLoadEarlierEntryVisibility()
        }
        viewModel.olderRemainingCount.observe(this) { count ->
            if (isFinishing || isDestroyed) return@observe
            olderRemainingCount = count ?: 0
            updateLoadEarlierEntryVisibility()
        }
        viewModel.sessionTitle.observe(this) { title ->
            if (isFinishing || isDestroyed) return@observe
            if (title.isNullOrEmpty()) return@observe
            supportActionBar?.title = title
            sessionOptions.sessionTitle = title
            updateToolbarModelSubtitle()
        }
        viewModel.streamDeltaEvent.observe(this) { event ->
            if (event == null) return@observe
            if (event.responseToken != activeResponseToken) return@observe
            if (isFinishing || isDestroyed) return@observe
            handleStreamDeltaEvent(event)
        }

        sessionOptions = resolveChatOptions()

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        quickModelSwitchView = findViewById(R.id.textQuickModelSwitch)
        quickModelSwitchView?.setOnClickListener { showQuickModelPicker() }
        firstDialoguePreviewView = findViewById(R.id.textFirstDialoguePreview)
        val btnSessionSettings: View? = findViewById(R.id.btnSessionSettings)
        btnSessionSettings?.setOnClickListener {
            startActivity(Intent(this, SessionChatSettingsActivity::class.java)
                .putExtra(SessionChatSettingsActivity.EXTRA_SESSION_ID, sessionId))
        }
        val btnSessionMore: View? = findViewById(R.id.btnSessionMore)
        btnSessionMore?.setOnClickListener { v -> showSessionMoreMenu(v) }
        val btnWriterOutline: View? = findViewById(R.id.btnWriterOutline)
        if (btnWriterOutline != null) {
            btnWriterOutline.visibility = if (writerAssistant) View.VISIBLE else View.GONE
            btnWriterOutline.setOnClickListener {
                if (!writerAssistant) return@setOnClickListener
                startActivity(Intent(this, SessionOutlineActivity::class.java)
                    .putExtra(SessionOutlineActivity.EXTRA_SESSION_ID, sessionId))
            }
        }

        val recyclerHistory: RecyclerView? = findViewById(R.id.recyclerHistory)
        val recyclerCurrent: RecyclerView? = findViewById(R.id.recyclerCurrent)
        val scrollMessages: NestedScrollView? = findViewById(R.id.scrollMessages)
        scrollMessagesView = scrollMessages
        val inputEdit: EditText? = findViewById(R.id.inputEdit)
        val sendButton: MaterialButton? = findViewById(R.id.sendButton)
        inputEditView = inputEdit
        sendButtonView = sendButton
        val btnAdd: View? = findViewById(R.id.btnAdd)
        val layoutAddActions: View? = findViewById(R.id.layoutAddActions)
        val btnAddFile: View? = findViewById(R.id.btnAddFile)
        val btnAddLocation: View? = findViewById(R.id.btnAddLocation)
        val btnAddTime: View? = findViewById(R.id.btnAddTime)
        val btnAddMore: View? = findViewById(R.id.btnAddMore)
        loadEarlierMessagesView = findViewById(R.id.textLoadEarlierMessages)
        loadEarlierMessagesView?.setOnClickListener { loadOlderMessages() }

        val headerHistory: View? = findViewById(R.id.headerHistory)
        expandHistoryView = findViewById(R.id.expandHistory)
        historyExpandIconView = findViewById(R.id.iconHistoryExpand)

        historyAdapter = MessageAdapter(assistantMarkdownStateStore)
        currentAdapter = MessageAdapter(assistantMarkdownStateStore)
        historyAdapter.setWriterMode(writerAssistant)
        currentAdapter.setWriterMode(writerAssistant)
        historyAdapter.setDisableAssistantCollapseToggle(characterAssistant)
        currentAdapter.setDisableAssistantCollapseToggle(characterAssistant)
        historyAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant)
        currentAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant)
        val assistantStateListener = object : MessageAdapter.OnAssistantStateChangedListener {
            override fun onAssistantStateChanged() {
                historyAdapter.notifyDataSetChanged()
                currentAdapter.notifyDataSetChanged()
            }
        }
        historyAdapter.setOnAssistantStateChangedListener(assistantStateListener)
        currentAdapter.setOnAssistantStateChangedListener(assistantStateListener)
        if (recyclerHistory != null) {
            recyclerHistory.layoutManager = LinearLayoutManager(this)
            recyclerHistory.isNestedScrollingEnabled = false
            recyclerHistory.adapter = historyAdapter
            disableChangeAnimations(recyclerHistory)
        }
        if (recyclerCurrent != null) {
            recyclerCurrent.layoutManager = LinearLayoutManager(this)
            recyclerCurrent.isNestedScrollingEnabled = false
            recyclerCurrent.adapter = currentAdapter
            disableChangeAnimations(recyclerCurrent)
        }
        bindMessageActions(historyAdapter)
        bindMessageActions(currentAdapter)
        setupAutoCollapseActions(recyclerHistory, recyclerCurrent, scrollMessages)

        if (headerHistory != null && expandHistoryView != null && historyExpandIconView != null) {
            headerHistory.setOnClickListener { setHistoryExpanded(!historyExpanded) }
        }

        if (btnAdd != null && layoutAddActions != null) {
            btnAdd.setOnClickListener {
                addActionsExpanded = !addActionsExpanded
                layoutAddActions.visibility = if (addActionsExpanded) View.VISIBLE else View.GONE
            }
        }
        btnAddFile?.setOnClickListener { Toast.makeText(this, "导入文件 TODO", Toast.LENGTH_SHORT).show() }
        btnAddLocation?.setOnClickListener { Toast.makeText(this, "添加位置 TODO", Toast.LENGTH_SHORT).show() }
        btnAddTime?.setOnClickListener { Toast.makeText(this, "添加时间 TODO", Toast.LENGTH_SHORT).show() }
        btnAddMore?.setOnClickListener { Toast.makeText(this, "更多功能 TODO", Toast.LENGTH_SHORT).show() }

        updateSendButtonState()
        sendButton?.setOnClickListener {
            if (assistantResponseInProgress) {
                stopLatestResponse()
                return@setOnClickListener
            }
            val text = inputEditView?.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) return@setOnClickListener
            inputEditView?.setText("")
            sendMessageFromText(text)
        }
        inputEdit?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) collapseMessageActions()
        }

        viewModel.init(sessionId)
    }

    private fun loadMessages() {
        viewModel.loadMessages()
    }

    private fun applyMessagesAndTitle() {
        if (isFinishing || isDestroyed) return
        updateFirstDialoguePreview()
        splitAndDisplay()
        scrollMessagesView?.post { updateCollapseToggleAffixViewport() }
        val sessionTitle = sessionOptions.sessionTitle
        if (!sessionTitle.isNullOrEmpty()) {
            supportActionBar?.title = sessionTitle.trim()
            updateToolbarModelSubtitle()
            return
        }
        val meta = SessionMetaStore(this).get(sessionId)
        if (meta != null && !meta.title.isNullOrEmpty()) {
            supportActionBar?.title = meta.title.trim()
            updateToolbarModelSubtitle()
            return
        }
        var title = ""
        for (m in allMessages) {
            if (m != null && m.role == Message.ROLE_USER && !m.content.isNullOrEmpty()) {
                title = if (m.content.length > 25) m.content.substring(0, 25) + "…" else m.content
                break
            }
        }
        supportActionBar?.title = if (title.isEmpty()) "新对话" else title
        updateToolbarModelSubtitle()
    }

    private fun splitAndDisplay() {
        if (isFinishing || isDestroyed) return
        assistantMarkdownStateStore.onAllMessagesChanged(allMessages)
        val cardHistory: View = findViewById(R.id.cardHistory) ?: return
        val textHistoryTitle: View? = findViewById(R.id.textHistoryTitle)
        val latestUser = findLatestByRole(Message.ROLE_USER)
        val latestAssistant = findLatestByRole(Message.ROLE_ASSISTANT)
        if (characterAssistant) {
            historyAdapter.setPinnedActionMessages(null, null, assistantResponseInProgress)
            currentAdapter.setPinnedActionMessages(null, null, assistantResponseInProgress)
        } else {
            historyAdapter.setPinnedActionMessages(latestUser, latestAssistant, assistantResponseInProgress)
            currentAdapter.setPinnedActionMessages(latestUser, latestAssistant, assistantResponseInProgress)
        }

        val total = allMessages.size
        if (total <= INITIAL_RENDER_MESSAGE_LIMIT) {
            cardHistory.visibility = View.GONE
            currentAdapter.setMessages(allMessages)
            historyAdapter.setMessages(ArrayList())
        } else {
            cardHistory.visibility = View.VISIBLE
            textHistoryTitle?.visibility = View.VISIBLE
            val split = total - INITIAL_RENDER_MESSAGE_LIMIT
            val history = ArrayList(allMessages.subList(0, split))
            val current = ArrayList(allMessages.subList(split, total))
            historyAdapter.setMessages(history)
            currentAdapter.setMessages(current)
            if (textHistoryTitle is TextView) {
                textHistoryTitle.text = "历史对话 (${history.size}条)"
            }
        }
        maybeAutoScrollToBottom(false)
    }

    private fun sendMessageFromText(text: String) {
        if (text.isEmpty()) return
        if (isFinishing || isDestroyed) return
        setAssistantResponseInProgress(true)
        activeResponseToken = viewModel.incrementResponseToken()
        val responseToken = activeResponseToken
        activeStreamingMessage = null
        activeChatHandle = null

        val userMsg = Message(sessionId, Message.ROLE_USER, text)
        viewModel.insertMessageAsync(userMsg)
        allMessages.add(userMsg)
        applyMessagesAndTitle()
        maybeAutoScrollToBottom(true)
        updateToolbarTitle(text)
        maybeAutoGenerateThreadTitle(text)

        var historyForApi: List<Message> = ArrayList(allMessages)
        if (historyForApi.isNotEmpty()) (historyForApi as MutableList).removeAt(historyForApi.size - 1)
        historyForApi = buildHistoryForApi(historyForApi)
        val options = resolveChatOptions()
        val shouldUseCharacterMemory = shouldUseCharacterMemory()
        if (shouldUseCharacterMemory) {
            reportCharacterInteractionAsync(CharacterMemoryApi.ROLE_USER, text)
        }
        val plainApiUserMessage = buildUserMessageForApi(text)
        val finalHistoryForApi = historyForApi
        val finalOptions = options
        if (shouldAutoChapterPlan(finalOptions)) {
            startChapterPlanFlow(finalHistoryForApi, text, plainApiUserMessage, finalOptions, responseToken, shouldUseCharacterMemory)
            return
        }
        dispatchChatRequestWithOptionalMemory(finalHistoryForApi, plainApiUserMessage, finalOptions, responseToken, shouldUseCharacterMemory)
    }

    private fun shouldAutoChapterPlan(options: SessionChatOptions?): Boolean {
        return writerAssistant && options != null && options.autoChapterPlan
    }

    private fun startChapterPlanFlow(
        historyForApi: List<Message>,
        originalInput: String,
        plainApiUserMessage: String,
        options: SessionChatOptions,
        responseToken: Long,
        shouldUseCharacterMemory: Boolean
    ) {
        if (isFinishing || isDestroyed) return
        val resolved = booleanArrayOf(false)
        val dialogController = showChapterPlanDialog(
            ChapterPlanDraft(),
            "正在生成章节计划…",
            object : ChapterPlanDialogCallback {
                override fun onCancel() {
                    resolved[0] = true
                    if (responseToken != activeResponseToken) return
                    setAssistantResponseInProgress(false)
                    activeChatHandle = null
                    activeStreamingMessage = null
                    removeCharacterMemoryLoadingPlaceholder()
                }

                override fun onConfirm(edited: ChapterPlanDraft?, addOutline: Boolean) {
                    resolved[0] = true
                    if (responseToken != activeResponseToken) return
                    if (edited == null || !edited.hasAnyContent()) {
                        dispatchChatRequestWithOptionalMemory(historyForApi, plainApiUserMessage, options, responseToken, shouldUseCharacterMemory)
                        return
                    }
                    persistLastTargetLength(edited.targetLength)
                    if (addOutline) {
                        addChapterPlanToOutline(edited)
                    }
                    val finalUserMessage = composeUserMessageWithChapterPlan(plainApiUserMessage, edited)
                    dispatchChatRequestWithOptionalMemory(historyForApi, finalUserMessage, options, responseToken, shouldUseCharacterMemory)
                }
            })
        chatService.generateChapterPlanJson(originalInput, plainApiUserMessage, object : ChatService.ChatCallback {
            override fun onPartial(delta: String) {
                mainHandler.post {
                    if (resolved[0]) return@post
                    if (responseToken != activeResponseToken) return@post
                    if (isFinishing || isDestroyed) return@post
                    dialogController?.setStatus(
                        if (delta.trim().isNotEmpty()) delta.trim()
                        else "正在生成章节计划…"
                    )
                }
            }

            override fun onSuccess(content: String) {
                mainHandler.post {
                    if (resolved[0]) return@post
                    if (responseToken != activeResponseToken) return@post
                    if (isFinishing || isDestroyed) return@post
                    val draft = parseChapterPlanDraft(content)
                    if (draft == null) {
                        dialogController?.setStatus("计划解析失败，可手动填写后确认继续")
                        return@post
                    }
                    dialogController?.applyGeneratedDraft(draft)
                    if (draft.hasAnyContent()) {
                        dialogController?.setStatus("章节计划已生成，可编辑后确认")
                    } else {
                        dialogController?.setStatus("已解析到结构，但字段为空；可手动填写后确认")
                    }
                }
            }

            override fun onError(message: String) {
                mainHandler.post {
                    if (resolved[0]) return@post
                    if (responseToken != activeResponseToken) return@post
                    if (isFinishing || isDestroyed) return@post
                    val msg = if (message.trim().isNotEmpty()) message.trim() else "章节计划生成失败"
                    dialogController?.setStatus("$msg。可手动填写后确认，或直接确认跳过计划。")
                }
            }
        })
    }

    private fun dispatchChatRequestWithOptionalMemory(
        historyForApi: List<Message>,
        plainApiUserMessage: String,
        options: SessionChatOptions,
        responseToken: Long,
        shouldUseCharacterMemory: Boolean
    ) {
        if (!shouldUseCharacterMemory) {
            dispatchChatRequest(historyForApi, plainApiUserMessage, options, responseToken, false)
            return
        }
        showCharacterMemoryLoadingPlaceholder(responseToken)
        executor.execute {
            var enrichedUserMessage = plainApiUserMessage
            try {
                val memory = characterMemoryService?.getMemoryContext(assistantId, sessionId, plainApiUserMessage)
                enrichedUserMessage = buildUserMessageForApiWithMemory(plainApiUserMessage, memory)
            } catch (e: Exception) {
                Log.w(TAG, "memory-context failed: ${e.message ?: ""}")
            }
            val finalUserMessage = enrichedUserMessage
            mainHandler.post {
                if (responseToken != activeResponseToken) return@post
                if (isFinishing || isDestroyed) return@post
                dispatchChatRequest(historyForApi, finalUserMessage, options, responseToken, true)
            }
        }
    }

    private fun dispatchChatRequest(
        historyForApi: List<Message>,
        apiUserMessage: String,
        options: SessionChatOptions,
        responseToken: Long,
        reportAssistantToMemory: Boolean
    ) {
        var streamingAssistant: Message?
        if (characterMemoryLoadingMessage != null) {
            // Reuse loading placeholder bubble to avoid a blank gap between loading and first token.
            streamingAssistant = characterMemoryLoadingMessage
            characterMemoryLoadingMessage = null
            if (streamingAssistant?.content.isNullOrEmpty()) {
                streamingAssistant?.content = CHARACTER_MEMORY_LOADING_TEXT
            }
            streamingAssistant?.thinkingRunning = false
            streamingAssistant?.thinkingStartedAt = 0L
            streamingAssistant?.thinkingElapsedMs = 0L
        } else {
            streamingAssistant = Message(sessionId, Message.ROLE_ASSISTANT, "")
            streamingAssistant.thinkingRunning = false
            streamingAssistant.thinkingStartedAt = 0L
            streamingAssistant.thinkingElapsedMs = 0L
            allMessages.add(streamingAssistant)
            applyMessagesAndTitle()
            maybeAutoScrollToBottom(true)
        }
        activeStreamingMessage = streamingAssistant
        streamingTargetMessage = streamingAssistant
        stopStreamTypewriter(true)
        try {
            activeChatHandle = viewModel.doChatRequest(
                historyForApi, apiUserMessage, options, responseToken,
                reportAssistantToMemory, assistantId, characterMemoryService!!)
        } catch (e: Exception) {
            setAssistantResponseInProgress(false)
            activeChatHandle = null
            activeStreamingMessage = null
            Toast.makeText(this, getString(R.string.error_send_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun handleStreamDeltaEvent(event: ChatViewModel.StreamDeltaEvent) {
        if (event.delta != null) {
            // onPartial
            val streamingMsg = activeStreamingMessage
            if (streamingMsg != null && CHARACTER_MEMORY_LOADING_TEXT == streamingMsg.content?.trim()) {
                streamingMsg.content = ""
                removeCharacterMemoryLoadingPlaceholder()
            }
            finishThinking(activeStreamingMessage)
            enqueueStreamDelta(activeStreamingMessage, event.delta)
        } else if (event.reasoning != null) {
            // onReasoning
            val streamingMsg = activeStreamingMessage
            if (streamingMsg != null && CHARACTER_MEMORY_LOADING_TEXT == streamingMsg.content?.trim()) {
                streamingMsg.content = ""
                removeCharacterMemoryLoadingPlaceholder()
            }
            beginThinking(activeStreamingMessage)
            activeStreamingMessage?.reasoning = event.reasoning ?: ""
            scheduleStreamRender()
        } else if (event.isUsage) {
            // onUsage
            activeStreamingMessage?.let { msg ->
                msg.promptTokens = event.promptTokens
                msg.completionTokens = event.completionTokens
                msg.totalTokens = event.totalTokens
                msg.elapsedMs = event.elapsedMs
                scheduleStreamRender()
            }
        } else if (event.isSuccess) {
            // onSuccess
            val shouldStick = autoScrollToBottomEnabled
            setAssistantResponseInProgress(false)
            activeChatHandle = null
            val streaming = activeStreamingMessage
            activeStreamingMessage = null
            val safeContent = event.successContent ?: ""
            removeCharacterMemoryLoadingPlaceholder()
            if (streaming != null) {
                finishThinking(streaming)
                stopStreamTypewriter(true)
                streaming.content = safeContent
                viewModel.persistSessionMessagesAsync(allMessages)
            } else {
                val assistantMsg = Message(sessionId, Message.ROLE_ASSISTANT, safeContent)
                allMessages.add(assistantMsg)
            }
            if (event.reportAssistantToMemory) {
                reportCharacterInteractionAsync(CharacterMemoryApi.ROLE_ASSISTANT, safeContent)
            }
            flushStreamRenderNow()
            maybeAutoScrollToBottom(shouldStick)
        } else if (event.isError) {
            // onError
            setAssistantResponseInProgress(false)
            activeChatHandle = null
            val streaming = activeStreamingMessage
            activeStreamingMessage = null
            removeCharacterMemoryLoadingPlaceholder()
            if (streaming != null) {
                finishThinking(streaming)
            }
            stopStreamTypewriter(true)
            if (streaming != null) {
                allMessages.remove(streaming)
                flushStreamRenderNow()
            }
            val errMsg = viewModel.errorEvent.value
            Toast.makeText(
                this,
                if (!errMsg.isNullOrEmpty()) errMsg else getString(R.string.error_request_failed),
                Toast.LENGTH_LONG
            ).show()
        } else if (event.isCancelled) {
            // onCancelled
            removeCharacterMemoryLoadingPlaceholder()
            handleResponseStopped(activeStreamingMessage, event.reportAssistantToMemory)
            activeStreamingMessage = null
        }
    }

    private fun updateToolbarTitle(userMsg: String?) {
        if (userMsg == null) return
        val sessionTitle = sessionOptions.sessionTitle
        if (!sessionTitle.isNullOrEmpty()) return
        val meta = SessionMetaStore(this).get(sessionId)
        if (meta != null && !meta.title.isNullOrEmpty()) return
        val title = if (userMsg.length > 25) userMsg.substring(0, 25) + "…" else userMsg
        supportActionBar?.title = title
    }

    private fun maybeAutoGenerateThreadTitle(firstUserMessage: String) {
        if (autoNamingInFlight) {
            Log.d(TAG, "skip auto title: already in flight")
            return
        }
        val sessionTitle = sessionOptions.sessionTitle
        if (!sessionTitle.isNullOrEmpty()) {
            Log.d(TAG, "skip auto title: session title already set")
            return
        }
        val metaStore = SessionMetaStore(this)
        val meta = metaStore.get(sessionId)
        if (meta != null && !meta.title.isNullOrEmpty()) {
            Log.d(TAG, "skip auto title: meta title already set")
            return
        }
        val userCount = countUserMessages()
        if (userCount != 1) {
            Log.d(TAG, "skip auto title: user message count = $userCount")
            return
        }
        val fallbackTitle = buildFallbackThreadTitle(firstUserMessage)
        persistSessionTitle(fallbackTitle, false)
        supportActionBar?.title = fallbackTitle
        updateToolbarModelSubtitle()
        autoNamingInFlight = true
        Log.d(TAG, "start auto title generation, sessionId=$sessionId")
        viewModel.generateThreadTitle(firstUserMessage, fallbackTitle)
        // Result arrives via viewModel.sessionTitle LiveData observer, which updates toolbar and sessionOptions.
        // autoNamingInFlight is reset when observer fires or on next loadMessages.
        autoNamingInFlight = false
    }

    private fun buildFallbackThreadTitle(userMessage: String?): String {
        val source = userMessage?.trim() ?: ""
        if (source.isEmpty()) return "新对话"
        return if (source.length > 10) source.substring(0, 10) else source
    }

    private fun persistSessionTitle(title: String, overwriteExisting: Boolean) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModel.persistSessionTitle(trimmed, overwriteExisting)
        // Update local sessionOptions mirror immediately on main thread
        val current = sessionOptions.sessionTitle?.trim() ?: ""
        if (overwriteExisting || current.isEmpty()) {
            sessionOptions.sessionTitle = trimmed
        }
    }

    private fun countUserMessages(): Int {
        var count = 0
        for (m in allMessages) {
            if (m != null && m.role == Message.ROLE_USER) count++
        }
        return count
    }

    private fun resolveChatOptions(): SessionChatOptions {
        val optionsStore = SessionChatOptionsStore(this)
        val fromSession = optionsStore.get(sessionId)
        sessionOptions = fromSession ?: SessionChatOptions()
        // Session-level settings are always the source of truth once initialized/saved.
        if (optionsStore.has(sessionId)) return fromSession!!

        val initialized = initializeSessionOptionsFromAssistantOrGlobal(fromSession)
        optionsStore.save(sessionId, initialized)
        sessionOptions = initialized
        return initialized
    }

    private fun initializeSessionOptionsFromAssistantOrGlobal(base: SessionChatOptions?): SessionChatOptions {
        var out = copyOptions(base)

        if (!assistantId.isNullOrEmpty()) {
            val assistant = MyAssistantStore(this).getById(assistantId!!)
            if (assistant != null) {
                if (assistant.options != null) {
                    out = copyOptions(assistant.options)
                }
                if (out.sessionAvatar.isNullOrEmpty()) {
                    out.sessionAvatar = AssistantAvatarHelper.resolveTextAvatar(assistant, assistant.name)
                }
            }
        }

        // Global default fallback: only fill model if still missing after assistant initialization.
        if (out.modelKey.isNullOrEmpty()) {
            val modelConfig = ModelConfig(this)
            var fallback = modelConfig.getChatPreset()
            if (fallback.isNullOrEmpty()) {
                fallback = modelConfig.getFirstAvailablePreset()
            }
            out.modelKey = fallback ?: ""
        }
        return out
    }

    private fun copyOptions(src: SessionChatOptions?): SessionChatOptions {
        val out = SessionChatOptions()
        if (src == null) return out
        out.sessionTitle = src.sessionTitle ?: ""
        out.sessionAvatar = src.sessionAvatar ?: ""
        out.contextMessageCount = src.contextMessageCount
        out.modelKey = src.modelKey ?: ""
        out.systemPrompt = src.systemPrompt ?: ""
        out.stop = src.stop ?: ""
        out.temperature = src.temperature
        out.topP = src.topP
        out.streamOutput = true
        out.autoChapterPlan = src.autoChapterPlan
        out.thinking = src.thinking
        out.googleThinkingBudget = src.googleThinkingBudget
        return out
    }

    override fun onDestroy() {
        // Keep in-flight response alive when leaving page/app.
        // It can still finish in background and be persisted to DB.
        activeChatHandle = null
        activeStreamingMessage = null
        stopStreamTypewriter(true)
        streamingTargetMessage = null
        mainHandler.removeCallbacks(streamRenderRunnable)
        streamRenderPending = false
        mainHandler.removeCallbacks(thinkingTicker)
        stopProactivePolling()
        activeThinkingMessage = null
        super.onDestroy()
    }

    private fun persistAssistantMessageDetached(content: String, reportAssistantToMemory: Boolean) {
        // ViewModel.onSuccess already persists the assistant message to DB.
        assistantResponseInProgress = false
        if (reportAssistantToMemory) {
            executor.execute { reportCharacterInteractionSafely(CharacterMemoryApi.ROLE_ASSISTANT, content) }
        }
    }

    override fun onResume() {
        super.onResume()
        writerAssistant = resolveWriterAssistant()
        characterAssistant = resolveCharacterAssistant()
        historyAdapter.setWriterMode(writerAssistant)
        currentAdapter.setWriterMode(writerAssistant)
        historyAdapter.setDisableAssistantCollapseToggle(characterAssistant)
        currentAdapter.setDisableAssistantCollapseToggle(characterAssistant)
        historyAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant)
        currentAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant)
        val btnWriterOutline: View? = findViewById(R.id.btnWriterOutline)
        btnWriterOutline?.visibility = if (writerAssistant) View.VISIBLE else View.GONE
        sessionOptions = resolveChatOptions()
        applyMessagesAndTitle()
        startProactivePollingIfNeeded()
    }

    override fun onPause() {
        stopProactivePolling()
        super.onPause()
    }

    private fun updateToolbarModelSubtitle() {
        supportActionBar?.subtitle = null
        val modelLabel = resolveCurrentModelLabel()
        if (modelLabel.isEmpty()) {
            quickModelSwitchView?.setText(getString(R.string.quick_model_switch_placeholder))
        } else {
            quickModelSwitchView?.setText(getString(R.string.quick_model_switch_value, modelLabel))
        }
    }

    private fun resolveCurrentModelLabel(): String {
        val modelKey = sessionOptions.modelKey ?: ""
        val option = ConfiguredModelPicker.Option.fromStorageKey(modelKey, this)
        val displayName = option?.displayName
        if (option != null && !displayName.isNullOrEmpty()) {
            return displayName.trim()
        }
        if (modelKey.contains(":")) {
            return modelKey.substring(modelKey.indexOf(':') + 1).trim()
        }
        return ""
    }

    private fun showQuickModelPicker() {
        val options = ConfiguredModelPicker.getConfiguredModels(this)
        if (options.isNullOrEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setMessage("请先在「模型管理」中添加厂商并添加模型")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_picker, null)
        val recycler: RecyclerView = dialogView.findViewById(R.id.recyclerOptions)
        recycler.layoutManager = LinearLayoutManager(this)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("快速切换模型")
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val currentModelKey = sessionOptions.modelKey ?: ""
        val adapter = ModelPickerAdapter(options, currentModelKey) { option ->
            sessionOptions.modelKey = option.getStorageKey()
            SessionChatOptionsStore(this).save(sessionId, sessionOptions)
            updateToolbarModelSubtitle()
            dialog.dismiss()
        }
        recycler.adapter = adapter
        dialog.show()
    }

    private fun showSessionMoreMenu(anchor: View) {
        val popupMenu = PopupMenu(this, anchor)
        popupMenu.menu.add(0, 1, 0, getString(R.string.quick_jump_chapters))
        popupMenu.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                showChapterJumpDialog()
                true
            } else {
                false
            }
        }
        popupMenu.show()
    }

    private fun showChapterJumpDialog() {
        val items = buildChapterJumpItems()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.no_assistant_chapters, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = Array(items.size) { i ->
            val one = items[i]
            getString(R.string.chapter_jump_item_format, one.index, one.preview)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quick_jump_chapters)
            .setItems(labels) { _, which ->
                if (which < 0 || which >= items.size) return@setItems
                val target = items[which]
                scrollToChapterMessage(target.createdAt, target.messageId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildChapterJumpItems(): List<ChapterJumpItem> {
        val out = ArrayList<ChapterJumpItem>()
        var chapterIndex = 1
        for (m in allMessages) {
            if (m == null || m.role != Message.ROLE_ASSISTANT) continue
            val content = m.content?.trim() ?: ""
            if (content.isEmpty()) continue
            val item = ChapterJumpItem()
            item.index = chapterIndex++
            item.messageId = m.id
            item.createdAt = m.createdAt
            item.preview = buildChapterPreview(content)
            out.add(item)
        }
        return out
    }

    private fun buildChapterPreview(content: String): String {
        val lines = content.split(Regex("\\r?\\n"))
        val sb = StringBuilder()
        var count = 0
        for (line in lines) {
            val one = line.trim()
            if (one.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(" / ")
            sb.append(one)
            count++
            if (count >= 2) break
        }
        var preview = if (sb.isNotEmpty()) sb.toString() else content.trim()
        if (preview.length > 60) {
            preview = preview.substring(0, 60) + "..."
        }
        return preview
    }

    private fun scrollToChapterMessage(createdAt: Long, messageId: Long) {
        if (scrollMessagesView == null) return
        if (containsMessage(historyAdapter, createdAt, messageId) && !historyExpanded) {
            setHistoryExpanded(true)
        }
        attemptScrollToChapterMessage(createdAt, messageId, 0)
    }

    private fun attemptScrollToChapterMessage(createdAt: Long, messageId: Long, attempt: Int) {
        if (scrollMessagesView == null) return
        var moved = scrollToMessageTopInRecycler(
            findViewById(R.id.recyclerHistory), historyAdapter, createdAt, messageId)
        if (!moved) {
            moved = scrollToMessageTopInRecycler(
                findViewById(R.id.recyclerCurrent), currentAdapter, createdAt, messageId)
        }
        if (moved) return
        if (attempt >= 12) {
            Toast.makeText(this, R.string.chapter_jump_failed, Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.postDelayed({ attemptScrollToChapterMessage(createdAt, messageId, attempt + 1) }, 60L)
    }

    private fun scrollToMessageTopInRecycler(
        recyclerView: RecyclerView?,
        adapter: MessageAdapter?,
        createdAt: Long,
        messageId: Long
    ): Boolean {
        if (recyclerView == null || adapter == null || scrollMessagesView == null) return false
        val list = adapter.getMessages()
        var pos = -1
        for (i in list.indices) {
            if (matchesJumpTarget(list[i], createdAt, messageId)) {
                pos = i
                break
            }
        }
        if (pos < 0) return false
        val layoutManager = recyclerView.layoutManager
        if (layoutManager is LinearLayoutManager) {
            layoutManager.scrollToPositionWithOffset(pos, 0)
        } else {
            recyclerView.scrollToPosition(pos)
        }
        val vh = recyclerView.findViewHolderForAdapterPosition(pos)
        var itemView: View? = vh?.itemView
        if (itemView == null) {
            itemView = layoutManager?.findViewByPosition(pos)
        }
        if (itemView == null) return false
        val timestampView: View? = itemView.findViewById(R.id.textTimestamp)
        val targetY = computeScrollYInContainer(timestampView ?: itemView)
        if (targetY < 0) return false
        val margin = (8f * resources.displayMetrics.density).toInt()
        scrollMessagesView?.smoothScrollTo(0, maxOf(0, targetY - margin))
        return true
    }

    private fun containsMessage(adapter: MessageAdapter?, createdAt: Long, messageId: Long): Boolean {
        if (adapter == null) return false
        for (one in adapter.getMessages()) {
            if (matchesJumpTarget(one, createdAt, messageId)) return true
        }
        return false
    }

    private fun matchesJumpTarget(one: Message?, createdAt: Long, messageId: Long): Boolean {
        if (one == null) return false
        if (messageId > 0 && one.id > 0) return one.id == messageId
        return createdAt > 0 && one.createdAt == createdAt
    }

    private fun computeScrollYInContainer(targetView: View?): Int {
        if (targetView == null || scrollMessagesView == null) return -1
        val child = scrollMessagesView!!.getChildAt(0) ?: return -1
        var y = 0
        var cursor: View? = targetView
        while (cursor != null && cursor != child) {
            y += cursor.top - cursor.scrollY
            val parent: ViewParent = cursor.parent
            if (parent !is View) return -1
            cursor = parent
        }
        return if (cursor == child) y else -1
    }

    private fun setHistoryExpanded(expanded: Boolean) {
        historyExpanded = expanded
        expandHistoryView?.visibility = if (expanded) View.VISIBLE else View.GONE
        historyExpandIconView?.rotation = if (expanded) 90f else 0f
    }

    private fun updateFirstDialoguePreview() {
        if (firstDialoguePreviewView == null) return
        val source = sessionOptions.systemPrompt?.trim() ?: ""
        if (source.isEmpty()) {
            firstDialoguePreviewView?.visibility = View.GONE
            return
        }
        val preview = buildFirstDialoguePreviewText(source, 200)
        firstDialoguePreviewView?.text = getString(R.string.system_prompt_preview_value, preview)
        firstDialoguePreviewView?.visibility = View.VISIBLE
    }

    private fun buildFirstDialoguePreviewText(text: String?, maxChars: Int): String {
        if (text == null) return ""
        val compact = text.trim()
        if (compact.length <= maxChars) return compact
        return compact.substring(0, maxChars) + "..."
    }

    private class ChapterJumpItem {
        var index = 0
        var preview = ""
        var messageId = 0L
        var createdAt = 0L
    }

    private fun maybeInsertAssistantOpeningMessage() {
        if (allMessages.isNotEmpty()) return
        var firstDialogue = ""
        if (!assistantId.isNullOrEmpty()) {
            val assistant = MyAssistantStore(this).getById(assistantId!!)
            if (assistant?.firstDialogue != null) {
                firstDialogue = assistant.firstDialogue.trim()
            }
        }
        if (firstDialogue.isEmpty()) return
        val opening = Message(sessionId, Message.ROLE_ASSISTANT, firstDialogue)
        allMessages.add(opening)
        viewModel.insertMessageAsync(opening)
    }

    private fun bindMessageActions(adapter: MessageAdapter?) {
        if (adapter == null) return
        adapter.setOnMessageActionListener(object : MessageAdapter.OnMessageActionListener {
            override fun onRegenerate(message: Message) {
                if (message.role != Message.ROLE_USER) return
                val idx = indexOf(message)
                if (idx < 0) return
                val text = message.content ?: ""
                while (allMessages.size > idx) allMessages.removeAt(allMessages.size - 1)
                applyMessagesAndTitle()
                persistSessionMessagesAsync()
                sendMessageFromText(text)
            }

            override fun onEdit(message: Message) {
                showEditDialog(message)
            }

            override fun onCopy(message: Message) {
                copyText(message.content ?: "")
            }

            override fun onOpen(message: Message) {
                openMessageInBrowser(message.content ?: "")
            }

            override fun onOutline(message: Message) {
                if (!writerAssistant) return
                summarizeMessageToOutline(message)
            }

            override fun onDelete(message: Message) {
                val idx = indexOf(message)
                if (idx < 0) return
                allMessages.removeAt(idx)
                applyMessagesAndTitle()
                persistSessionMessagesAsync()
            }
        })
    }

    private fun summarizeMessageToOutline(message: Message) {
        val source = message.content?.trim() ?: ""
        if (source.isEmpty()) {
            Toast.makeText(this, "消息为空，无法提取", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在提取到大纲…", Toast.LENGTH_SHORT).show()
        chatService.summarizeMessageForOutline(source, object : ChatService.ChatCallback {
            override fun onSuccess(content: String) {
                mainHandler.post {
                    val summary = content.trim()
                    if (summary.isEmpty()) {
                        onError("提取结果为空")
                        return@post
                    }
                    val next = outlineStore!!.nextChapterIndex(sessionId)
                    val title = "章节$next"
                    outlineStore!!.add(sessionId, "chapter", title, summary)
                    Toast.makeText(this@ChatSessionActivity, "已添加到大纲：$title", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(message: String) {
                mainHandler.post {
                    Toast.makeText(
                        this@ChatSessionActivity,
                        if (message.isNotEmpty()) message else "提取失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }

    private fun showChapterPlanDialog(
        draft: ChapterPlanDraft,
        initialStatus: String,
        callback: ChapterPlanDialogCallback
    ): ChapterPlanDialogController? {
        if (draft.targetLength.isNullOrEmpty()) {
            draft.targetLength = getDefaultTargetLength()
        }
        val view = layoutInflater.inflate(R.layout.dialog_chapter_plan, null)
        val textStatus: TextView? = view.findViewById(R.id.textPlanGenerationStatus)
        val editGoal: TextInputEditText? = view.findViewById(R.id.editPlanChapterGoal)
        val editStart: TextInputEditText? = view.findViewById(R.id.editPlanStartState)
        val editEnd: TextInputEditText? = view.findViewById(R.id.editPlanEndState)
        val editDrives: TextInputEditText? = view.findViewById(R.id.editPlanCharacterDrives)
        val editKnowledge: TextInputEditText? = view.findViewById(R.id.editPlanKnowledgeBoundary)
        val editEvents: TextInputEditText? = view.findViewById(R.id.editPlanEventChain)
        val editForeshadow: TextInputEditText? = view.findViewById(R.id.editPlanForeshadow)
        val editPayoff: TextInputEditText? = view.findViewById(R.id.editPlanPayoff)
        val editForbidden: TextInputEditText? = view.findViewById(R.id.editPlanForbidden)
        val editStyle: TextInputEditText? = view.findViewById(R.id.editPlanStyleGuide)
        val editLength: TextInputEditText? = view.findViewById(R.id.editPlanTargetLength)

        val controller = ChapterPlanDialogController(
            null, textStatus,
            editGoal, editStart, editEnd, editDrives, editKnowledge, editEvents,
            editForeshadow, editPayoff, editForbidden, editStyle, editLength
        )
        controller.applyDraft(draft, false)
        controller.setStatus(initialStatus)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("本轮写作计划")
            .setView(view)
            .setNegativeButton("取消") { _, _ -> callback.onCancel() }
            .setNeutralButton("确认并加入大纲") { _, _ ->
                callback.onConfirm(
                    collectChapterPlanDraft(
                        controller.editGoal, controller.editStart, controller.editEnd,
                        controller.editDrives, controller.editKnowledge, controller.editEvents,
                        controller.editForeshadow, controller.editPayoff, controller.editForbidden,
                        controller.editStyle, controller.editLength
                    ), true
                )
            }
            .setPositiveButton("确认") { _, _ ->
                callback.onConfirm(
                    collectChapterPlanDraft(
                        controller.editGoal, controller.editStart, controller.editEnd,
                        controller.editDrives, controller.editKnowledge, controller.editEvents,
                        controller.editForeshadow, controller.editPayoff, controller.editForbidden,
                        controller.editStyle, controller.editLength
                    ), false
                )
            }
            .show()
        controller.dialog = dialog
        return controller
    }

    private fun collectChapterPlanDraft(
        editGoal: TextInputEditText?,
        editStart: TextInputEditText?,
        editEnd: TextInputEditText?,
        editDrives: TextInputEditText?,
        editKnowledge: TextInputEditText?,
        editEvents: TextInputEditText?,
        editForeshadow: TextInputEditText?,
        editPayoff: TextInputEditText?,
        editForbidden: TextInputEditText?,
        editStyle: TextInputEditText?,
        editLength: TextInputEditText?
    ): ChapterPlanDraft {
        val draft = ChapterPlanDraft()
        draft.chapterGoal = textOf(editGoal)
        draft.startState = textOf(editStart)
        draft.endState = textOf(editEnd)
        draft.characterDrives = ChapterPlanDraft.parseCharacterDrives(textOf(editDrives))
        draft.knowledgeBoundary = parseLines(textOf(editKnowledge))
        draft.eventChain = parseLines(textOf(editEvents))
        draft.foreshadow = parseLines(textOf(editForeshadow))
        draft.payoff = parseLines(textOf(editPayoff))
        draft.forbidden = parseLines(textOf(editForbidden))
        draft.styleGuide = textOf(editStyle)
        draft.targetLength = textOf(editLength)
        return draft
    }

    private fun composeUserMessageWithChapterPlan(plainApiUserMessage: String, draft: ChapterPlanDraft): String {
        val base = plainApiUserMessage.trim()
        val plan = draft.toJson()
        val sb = StringBuilder()
        if (base.isNotEmpty()) {
            sb.append(base).append("\n\n")
        }
        sb.append("【本轮写作计划（必须遵循）】\n")
            .append(plan.toString())
            .append("\n\n")
            .append("执行要求：优先遵循本轮计划推进剧情；不得违背知情约束与既有设定。")
        return sb.toString().trim()
    }

    private fun addChapterPlanToOutline(draft: ChapterPlanDraft) {
        val next = outlineStore!!.nextChapterIndex(sessionId)
        val title = "章节$next"
        val content = draft.toOutlineText()
        outlineStore!!.add(sessionId, "chapter", title, content)
        Toast.makeText(this, "已加入大纲：$title", Toast.LENGTH_SHORT).show()
    }

    private fun parseChapterPlanDraft(json: String?): ChapterPlanDraft? {
        val raw = json?.trim() ?: ""
        if (raw.isEmpty()) return null
        return try {
            val obj = JsonParser().parse(raw).asJsonObject
            ChapterPlanDraft.fromJson(obj)
        } catch (e: Exception) {
            null
        }
    }

    private fun textOf(edit: TextInputEditText?): String {
        if (edit == null || edit.text == null) return ""
        return edit.text.toString().trim()
    }

    private fun parseLines(text: String): List<String> {
        val out = ArrayList<String>()
        if (text.trim().isEmpty()) return out
        val lines = text.split(Regex("\\r?\\n"))
        for (line in lines) {
            val one = line.trim()
            if (one.isNotEmpty()) out.add(one)
        }
        return out
    }

    private fun joinLines(items: List<String>?): String {
        if (items.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        for (one in items) {
            if (one.trim().isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(one.trim())
        }
        return sb.toString()
    }

    private fun getDefaultTargetLength(): String {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_CHAPTER_PLAN, MODE_PRIVATE)
        val saved = prefs.getString(KEY_LAST_TARGET_LENGTH, "")
        if (!saved.isNullOrEmpty()) return saved.trim()
        return DEFAULT_TARGET_LENGTH
    }

    private fun persistLastTargetLength(targetLength: String?) {
        val value = targetLength?.trim() ?: ""
        if (value.isEmpty()) return
        getSharedPreferences(PREFS_CHAPTER_PLAN, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_TARGET_LENGTH, value)
            .apply()
    }

    private fun buildUserMessageForApi(text: String): String {
        val source = text.trim()
        if (!writerAssistant || source.isEmpty()) return source
        val outlines = outlineStore?.getAll(sessionId)
        if (outlines.isNullOrEmpty()) return source
        val sb = StringBuilder()
        val knowledgeSb = StringBuilder()
        sb.append(source).append("\n\n")
        sb.append("【写作大纲与资料】\n")
        for (one in outlines) {
            if (one == null) continue
            val type = when (one.type) {
                "material" -> "资料"
                "task" -> "人物资料"
                "world" -> "世界背景"
                "knowledge" -> "知情约束"
                else -> "章节"
            }
            val title = one.title?.trim() ?: ""
            val content = one.content?.trim() ?: ""
            if (title.isEmpty() && content.isEmpty()) continue
            sb.append("- [").append(type).append("] ")
            if (title.isNotEmpty()) sb.append(title)
            if (content.isNotEmpty()) {
                if (title.isNotEmpty()) sb.append("：")
                sb.append(content)
            }
            sb.append("\n")
            if ("knowledge" == one.type) {
                knowledgeSb.append("- ")
                if (title.isNotEmpty()) knowledgeSb.append(title)
                if (content.isNotEmpty()) {
                    if (title.isNotEmpty()) knowledgeSb.append("：")
                    knowledgeSb.append(content)
                }
                knowledgeSb.append("\n")
            }
        }
        if (knowledgeSb.isNotEmpty()) {
            sb.append("\n【知情约束（必须遵守）】\n")
            sb.append(knowledgeSb)
            sb.append("1) 角色只能使用其已知信息行动、发言与推理。\n")
            sb.append("2) 未知信息不得被角色直接提及或据此决策。\n")
            sb.append("3) 若需要让角色得知信息，必须先写出获取路径（目击/对话/文件/推理）。\n")
            sb.append("4) 优先保证知情边界，不要把读者已知当成角色已知。\n")
        }
        sb.append("请严格参考以上内容，保持情节、设定、任务线索的一致性与准确性。")
        return sb.toString().trim()
    }

    private fun buildUserMessageForApiWithMemory(
        baseUserMessage: String,
        memory: CharacterMemoryApi.MemoryContextResponse?
    ): String {
        val source = baseUserMessage.trim()
        if (source.isEmpty()) return source
        if (memory == null || !memory.shouldUseMemory) return source
        val guidance = memory.memoryGuidance?.trim() ?: ""
        if (guidance.isEmpty()) return source
        val maxChars = 1200
        val truncated = if (guidance.length > maxChars) guidance.substring(0, maxChars) else guidance
        return "$source\n\n【角色长期记忆参考】\n$truncated"
    }

    private fun buildHistoryForApi(sourceHistory: List<Message>): List<Message> {
        val source = sourceHistory.ifEmpty { return emptyList() }
        if (!writerAssistant) return source
        var lastAssistantIndex = -1
        for (i in source.indices.reversed()) {
            val one = source[i]
            if (one != null && one.role == Message.ROLE_ASSISTANT) {
                lastAssistantIndex = i
                break
            }
        }
        val out = ArrayList<Message>(source.size)
        for (i in source.indices) {
            val m = source[i] ?: continue
            var content = m.content ?: ""
            if (m.role == Message.ROLE_ASSISTANT) {
                content = if (i == lastAssistantIndex) {
                    buildLastAssistantExcerpt(content)
                } else if (content.length > WRITER_ASSISTANT_CONTEXT_EXCERPT_MAX_CHARS) {
                    val excerpt = content.substring(0, WRITER_ASSISTANT_CONTEXT_EXCERPT_MAX_CHARS)
                    "【节选说明】以下内容为较早助手回复的前${WRITER_ASSISTANT_CONTEXT_EXCERPT_MAX_CHARS}字节选，用于保留关键语气与事实锚点；完整情节请以写作大纲与资料为准。\n$excerpt"
                } else content
            }
            out.add(Message(sessionId, m.role, content))
        }
        return out
    }

    private fun buildLastAssistantExcerpt(content: String): String {
        val source = content
        val total = source.length
        val segment = WRITER_ASSISTANT_LAST_SEGMENT_CHARS
        if (total <= segment * 3) {
            return source
        }
        val start = source.substring(0, segment)
        val middleStart = maxOf(0, (total - segment) / 2)
        val middle = source.substring(middleStart, middleStart + segment)
        val end = source.substring(total - segment)
        return "【节选说明】以下内容为最近一条助手回复的分段节选（前${segment}字 / 中间${segment}字 / 后${segment}字），用于保留上下文细节与风格连续性；完整情节请以写作大纲与资料为准。\n" +
                "【前段】\n$start\n【中段】\n$middle\n【后段】\n$end"
    }

    private interface ChapterPlanDialogCallback {
        fun onCancel()
        fun onConfirm(edited: ChapterPlanDraft?, addOutline: Boolean)
    }

    private class ChapterPlanDialogController(
        var dialog: AlertDialog?,
        val textStatus: TextView?,
        val editGoal: TextInputEditText?,
        val editStart: TextInputEditText?,
        val editEnd: TextInputEditText?,
        val editDrives: TextInputEditText?,
        val editKnowledge: TextInputEditText?,
        val editEvents: TextInputEditText?,
        val editForeshadow: TextInputEditText?,
        val editPayoff: TextInputEditText?,
        val editForbidden: TextInputEditText?,
        val editStyle: TextInputEditText?,
        val editLength: TextInputEditText?
    ) {
        fun setStatus(status: String?) {
            val text = status?.trim() ?: ""
            textStatus?.text = if (text.isEmpty()) "正在生成章节计划…" else text
        }

        fun applyGeneratedDraft(draft: ChapterPlanDraft) {
            applyDraft(draft, true)
        }

        fun applyDraft(draft: ChapterPlanDraft, fillOnlyEmpty: Boolean) {
            applyText(editGoal, draft.chapterGoal, fillOnlyEmpty)
            applyText(editStart, draft.startState, fillOnlyEmpty)
            applyText(editEnd, draft.endState, fillOnlyEmpty)
            applyText(editDrives, draft.characterDrivesToMultiline(), fillOnlyEmpty)
            applyText(editKnowledge, joinLinesStatic(draft.knowledgeBoundary), fillOnlyEmpty)
            applyText(editEvents, joinLinesStatic(draft.eventChain), fillOnlyEmpty)
            applyText(editForeshadow, joinLinesStatic(draft.foreshadow), fillOnlyEmpty)
            applyText(editPayoff, joinLinesStatic(draft.payoff), fillOnlyEmpty)
            applyText(editForbidden, joinLinesStatic(draft.forbidden), fillOnlyEmpty)
            applyText(editStyle, draft.styleGuide, fillOnlyEmpty)
            applyText(editLength, draft.targetLength, fillOnlyEmpty)
        }

        private fun applyText(edit: TextInputEditText?, value: String?, fillOnlyEmpty: Boolean) {
            if (edit == null) return
            val incoming = value ?: ""
            if (fillOnlyEmpty) {
                val current = edit.text?.toString()?.trim() ?: ""
                if (current.isNotEmpty()) return
            }
            edit.setText(incoming)
        }

        companion object {
            fun joinLinesStatic(items: List<String>?): String {
                if (items.isNullOrEmpty()) return ""
                val sb = StringBuilder()
                for (one in items) {
                    if (one.trim().isEmpty()) continue
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(one.trim())
                }
                return sb.toString()
            }
        }
    }

    private class ChapterPlanDraft {
        var chapterGoal: String = ""
        var startState: String = ""
        var endState: String = ""
        var characterDrives: MutableList<CharacterDrive> = ArrayList()
        var knowledgeBoundary: List<String> = ArrayList()
        var eventChain: List<String> = ArrayList()
        var foreshadow: List<String> = ArrayList()
        var payoff: List<String> = ArrayList()
        var forbidden: List<String> = ArrayList()
        var styleGuide: String = ""
        var targetLength: String = ""

        companion object {
            fun fromJson(obj: JsonObject?): ChapterPlanDraft {
                val out = ChapterPlanDraft()
                if (obj == null) return out
                out.chapterGoal = getString(obj, "chapterGoal")
                out.startState = getString(obj, "startState")
                out.endState = getString(obj, "endState")
                out.characterDrives = parseCharacterDrives(obj.get("characterDrives"))
                out.knowledgeBoundary = parseStringArray(obj.get("knowledgeBoundary"))
                out.eventChain = parseStringArray(obj.get("eventChain"))
                out.foreshadow = parseStringArray(obj.get("foreshadow"))
                out.payoff = parseStringArray(obj.get("payoff"))
                out.forbidden = parseStringArray(obj.get("forbidden"))
                out.styleGuide = getString(obj, "styleGuide")
                out.targetLength = getString(obj, "targetLength")
                return out
            }

            fun parseCharacterDrives(multiline: String?): MutableList<CharacterDrive> {
                val out = ArrayList<CharacterDrive>()
                if (multiline.isNullOrEmpty()) return out
                val lines = multiline.split(Regex("\\r?\\n"))
                for (line in lines) {
                    if (line.trim().isEmpty()) continue
                    val parts = line.split("|", limit = -1)  // -1 keeps trailing empty
                    val drive = CharacterDrive()
                    drive.name = if (parts.isNotEmpty()) parts[0].trim() else ""
                    drive.goal = if (parts.size > 1) parts[1].trim() else ""
                    drive.misbelief = if (parts.size > 2) parts[2].trim() else ""
                    drive.emotion = if (parts.size > 3) parts[3].trim() else ""
                    out.add(drive)
                }
                return out
            }

            fun parseCharacterDrives(element: JsonElement?): MutableList<CharacterDrive> {
                val out = ArrayList<CharacterDrive>()
                if (element == null || element.isJsonNull || !element.isJsonArray) return out
                val arr = element.asJsonArray
                for (i in 0 until arr.size()) {
                    val one = arr[i]
                    if (one == null || one.isJsonNull) continue
                    val drive = CharacterDrive()
                    if (one.isJsonObject) {
                        val obj = one.asJsonObject
                        drive.name = getString(obj, "name")
                        drive.goal = getString(obj, "goal")
                        drive.misbelief = getString(obj, "misbelief")
                        drive.emotion = getString(obj, "emotion")
                    } else {
                        drive.goal = if (one.isJsonPrimitive) one.asString else one.toString()
                    }
                    out.add(drive)
                }
                return out
            }

            private fun parseStringArray(element: JsonElement?): List<String> {
                val out = ArrayList<String>()
                if (element == null || element.isJsonNull || !element.isJsonArray) return out
                val arr = element.asJsonArray
                for (i in 0 until arr.size()) {
                    val one = arr[i]
                    if (one == null || one.isJsonNull) continue
                    val text = if (one.isJsonPrimitive) one.asString else one.toString()
                    if (text.trim().isNotEmpty()) out.add(text.trim())
                }
                return out
            }

            private fun toJsonArray(source: List<String>?): JsonArray {
                val out = JsonArray()
                if (source == null) return out
                for (one in source) {
                    if (one.trim().isEmpty()) continue
                    out.add(one.trim())
                }
                return out
            }

            private fun getString(obj: JsonObject?, key: String): String {
                if (obj == null || !obj.has(key)) return ""
                return try {
                    val e = obj.get(key)
                    if (e == null || e.isJsonNull) ""
                    else if (e.isJsonPrimitive) e.asString
                    else e.toString()
                } catch (ignored: Exception) {
                    ""
                }
            }
        }

        fun toJson(): JsonObject {
            val out = JsonObject()
            out.addProperty("chapterGoal", chapterGoal)
            out.addProperty("startState", startState)
            out.addProperty("endState", endState)
            val drives = JsonArray()
            for (one in characterDrives) {
                val item = JsonObject()
                item.addProperty("name", one.name)
                item.addProperty("goal", one.goal)
                item.addProperty("misbelief", one.misbelief)
                item.addProperty("emotion", one.emotion)
                drives.add(item)
            }
            out.add("characterDrives", drives)
            out.add("knowledgeBoundary", Companion.toJsonArray(knowledgeBoundary))
            out.add("eventChain", Companion.toJsonArray(eventChain))
            out.add("foreshadow", Companion.toJsonArray(foreshadow))
            out.add("payoff", Companion.toJsonArray(payoff))
            out.add("forbidden", Companion.toJsonArray(forbidden))
            out.addProperty("styleGuide", styleGuide)
            val length = targetLength.trim()
            if (length.isNotEmpty()) {
                out.addProperty("targetLength", length)
            }
            return out
        }

        fun hasAnyContent(): Boolean {
            if (chapterGoal.trim().isNotEmpty()) return true
            if (startState.trim().isNotEmpty()) return true
            if (endState.trim().isNotEmpty()) return true
            if (styleGuide.trim().isNotEmpty()) return true
            if (targetLength.trim().isNotEmpty()) return true
            if (characterDrives.isNotEmpty()) return true
            if (knowledgeBoundary.isNotEmpty()) return true
            if (eventChain.isNotEmpty()) return true
            if (foreshadow.isNotEmpty()) return true
            if (payoff.isNotEmpty()) return true
            return forbidden.isNotEmpty()
        }

        fun toOutlineText(): String {
            val sb = StringBuilder()
            if (chapterGoal.trim().isNotEmpty()) sb.append("目标：${chapterGoal.trim()}\n")
            if (startState.trim().isNotEmpty()) sb.append("起始状态：${startState.trim()}\n")
            if (endState.trim().isNotEmpty()) sb.append("结束状态：${endState.trim()}\n")
            if (eventChain.isNotEmpty()) {
                sb.append("事件链：")
                for (i in eventChain.indices) {
                    val one = eventChain[i]
                    if (one.trim().isEmpty()) continue
                    if (i > 0) sb.append(" -> ")
                    sb.append(one.trim())
                }
                sb.append("\n")
            }
            if (styleGuide.trim().isNotEmpty()) sb.append("文风：${styleGuide.trim()}\n")
            if (targetLength.trim().isNotEmpty()) sb.append("建议篇幅：${targetLength.trim()}")
            return sb.toString().trim()
        }

        fun characterDrivesToMultiline(): String {
            if (characterDrives.isEmpty()) return ""
            val sb = StringBuilder()
            for (one in characterDrives) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(one.name).append("|").append(one.goal)
                    .append("|").append(one.misbelief).append("|").append(one.emotion)
            }
            return sb.toString()
        }
    }

    private class CharacterDrive {
        var name = ""
        var goal = ""
        var misbelief = ""
        var emotion = ""
    }

    private fun resolveWriterAssistant(): Boolean {
        if (assistantId.isNullOrEmpty()) return false
        val assistant = MyAssistantStore(this).getById(assistantId!!)
        return assistant != null && "writer" == assistant.type
    }

    private fun resolveCharacterAssistant(): Boolean {
        if (assistantId.isNullOrEmpty()) return false
        val assistant = MyAssistantStore(this).getById(assistantId!!)
        return assistant != null && "character" == assistant.type
    }

    private fun shouldUseCharacterMemory(): Boolean {
        return characterAssistant
                && !assistantId.isNullOrEmpty()
                && characterMemoryService != null
                && characterMemoryService!!.isEnabled()
    }

    private fun shouldEnableProactivePolling(): Boolean {
        if (!shouldUseCharacterMemory()) return false
        val assistant = MyAssistantStore(this).getById(assistantId!!)
        return assistant != null && assistant.allowProactiveMessage
    }

    private fun startProactivePollingIfNeeded() {
        stopProactivePolling()
        if (!shouldEnableProactivePolling()) return
        proactivePollingActive = true
        mainHandler.post(proactivePollRunnable)
    }

    private fun stopProactivePolling() {
        proactivePollingActive = false
        mainHandler.removeCallbacks(proactivePollRunnable)
    }

    private fun showCharacterMemoryLoadingPlaceholder(responseToken: Long) {
        if (responseToken != activeResponseToken) return
        if (!shouldUseCharacterMemory()) return
        removeCharacterMemoryLoadingPlaceholder()
        val loading = Message(sessionId, Message.ROLE_ASSISTANT, CHARACTER_MEMORY_LOADING_TEXT)
        loading.createdAt = System.currentTimeMillis()
        characterMemoryLoadingMessage = loading
        allMessages.add(loading)
        applyMessagesAndTitle()
        maybeAutoScrollToBottom(true)
    }

    private fun removeCharacterMemoryLoadingPlaceholder() {
        val loading = characterMemoryLoadingMessage ?: return
        allMessages.remove(loading)
        characterMemoryLoadingMessage = null
        applyMessagesAndTitle()
    }

    private fun reportCharacterInteractionAsync(role: String, content: String) {
        executor.execute { reportCharacterInteractionSafely(role, content) }
    }

    private fun reportCharacterInteractionSafely(role: String, content: String) {
        if (!shouldUseCharacterMemory()) return
        val safeRole = role.trim()
        val safeContent = content.trim()
        if (safeRole.isEmpty() || safeContent.isEmpty()) return
        try {
            characterMemoryService?.reportInteraction(assistantId, sessionId, safeRole, safeContent)
        } catch (e: Exception) {
            Log.w(TAG, "report-interaction failed: ${e.message ?: ""}")
        }
    }

    private fun indexOf(target: Message?): Int {
        if (target == null) return -1
        for (i in allMessages.indices) {
            if (allMessages[i] === target) return i
        }
        return -1
    }

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun openMessageInBrowser(text: String) {
        val source = text.trim()
        if (source.isEmpty()) {
            Toast.makeText(this, R.string.error_message_empty_cannot_open, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val out = File(cacheDir, "message-view.html")
            val html = buildMessageHtml(source)
            FileOutputStream(out, false).use { fos ->
                fos.write(html.toByteArray(StandardCharsets.UTF_8))
            }
            val authority = "$packageName.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, out)

            val edgeIntent = Intent(Intent.ACTION_VIEW)
            edgeIntent.setDataAndType(uri, "text/html")
            edgeIntent.setPackage("com.microsoft.emmx")
            edgeIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(edgeIntent)
                return
            } catch (ignored: Exception) {}

            val browserIntent = Intent(Intent.ACTION_VIEW)
            browserIntent.setDataAndType(uri, "text/html")
            browserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(browserIntent, getString(R.string.chooser_browser)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_open_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun buildMessageHtml(content: String): String {
        val safe = escapeHtml(content)
        return "<!doctype html><html><head><meta charset=\"utf-8\"/>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
                "<title>Message</title>" +
                "<style>body{font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;" +
                "padding:18px;line-height:1.7;font-size:18px;white-space:pre-wrap;color:#111;}" +
                "</style></head><body>$safe</body></html>"
    }

    private fun escapeHtml(source: String): String {
        if (source.isEmpty()) return ""
        return source
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun showEditDialog(message: Message) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_message, null)
        val edit = view.findViewById<android.widget.EditText>(R.id.editMessageContent)
        edit?.setText(message.content ?: "")
        edit?.setSelection(edit.text?.length ?: 0)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<android.view.View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<android.view.View>(R.id.btnConfirm)?.setOnClickListener {
            val content = edit?.text?.toString()?.trim() ?: ""
            message.content = content
            applyMessagesAndTitle()
            persistSessionMessagesAsync()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun persistSessionMessagesAsync() {
        viewModel.persistSessionMessagesAsync(allMessages)
    }

    private fun setupAutoCollapseActions(
        recyclerHistory: RecyclerView?,
        recyclerCurrent: RecyclerView?,
        scrollMessages: NestedScrollView?
    ) {
        val touchCollapse = View.OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) collapseMessageActions()
            false
        }
        if (recyclerHistory != null) {
            recyclerHistory.setOnTouchListener(touchCollapse)
            recyclerHistory.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0 || dx != 0) collapseMessageActions()
                }
            })
        }
        if (recyclerCurrent != null) {
            recyclerCurrent.setOnTouchListener(touchCollapse)
            recyclerCurrent.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0 || dx != 0) collapseMessageActions()
                }
            })
        }
        scrollMessages?.setOnTouchListener(touchCollapse)
        scrollMessages?.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, scrollX, scrollY, oldScrollX, oldScrollY ->
            updateAutoScrollStateFromPosition()
            if (scrollY != oldScrollY || scrollX != oldScrollX) collapseMessageActions()
            updateLoadEarlierEntryVisibility()
            updateCollapseToggleAffixViewport()
        })
    }

    private fun updateCollapseToggleAffixViewport() {
        val scroll = scrollMessagesView ?: return
        val rect = Rect()
        if (!scroll.getGlobalVisibleRect(rect)) return
        historyAdapter.setCollapseToggleAffixViewport(rect.top, rect.bottom)
        currentAdapter.setCollapseToggleAffixViewport(rect.top, rect.bottom)
    }

    private fun disableChangeAnimations(recyclerView: RecyclerView?) {
        if (recyclerView == null) return
        val animator = recyclerView.itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }
    }

    private fun collapseMessageActions() {
        historyAdapter.clearFocus()
        currentAdapter.clearFocus()
    }

    private fun setAssistantResponseInProgress(inProgress: Boolean) {
        assistantResponseInProgress = inProgress
        updateSendButtonState()
    }

    private fun updateSendButtonState() {
        val btn = sendButtonView ?: return
        btn.text = ""
        if (assistantResponseInProgress) {
            btn.setIconResource(R.drawable.ic_action_stop)
        } else {
            btn.setIconResource(android.R.drawable.ic_menu_send)
        }
    }

    private fun stopLatestResponse() {
        val handle = activeChatHandle
        val target = activeStreamingMessage
        activeResponseToken = viewModel.incrementResponseToken()
        activeChatHandle = null
        activeStreamingMessage = null
        removeCharacterMemoryLoadingPlaceholder()
        try {
            handle?.cancel()
        } catch (ignored: Exception) {}
        handleResponseStopped(target, shouldUseCharacterMemory())
    }

    private fun handleResponseStopped(streamingMessage: Message?, reportAssistantToMemory: Boolean) {
        val shouldStickBottomAfterDone = autoScrollToBottomEnabled
        setAssistantResponseInProgress(false)
        if (streamingMessage != null) {
            finishThinking(streamingMessage)
            drainPendingStreamCharsTo(streamingMessage)
            val hasContent = !streamingMessage.content.isNullOrEmpty()
            val hasReasoning = !streamingMessage.reasoning.isNullOrEmpty()
            if (!hasContent && !hasReasoning) {
                allMessages.remove(streamingMessage)
            } else {
                persistSessionMessagesAsync()
                if (reportAssistantToMemory && hasContent) {
                    reportCharacterInteractionAsync(CharacterMemoryApi.ROLE_ASSISTANT, streamingMessage.content!!)
                }
            }
        } else {
            stopStreamTypewriter(true)
        }
        flushStreamRenderNow()
        maybeAutoScrollToBottom(shouldStickBottomAfterDone)
    }

    private fun scheduleStreamRender() {
        val throttle = if (pendingStreamChars.length >= STREAM_RENDER_BUSY_PENDING_CHARS)
            STREAM_RENDER_THROTTLE_BUSY_MS else STREAM_RENDER_THROTTLE_MS
        val now = System.currentTimeMillis()
        val wait = maxOf(0L, throttle - (now - lastStreamRenderAt))
        if (streamRenderPending) return
        streamRenderPending = true
        mainHandler.postDelayed(streamRenderRunnable, wait)
    }

    private fun flushStreamRenderNow() {
        mainHandler.removeCallbacks(streamRenderRunnable)
        streamRenderPending = false
        lastStreamRenderAt = System.currentTimeMillis()
        applyMessagesAndTitle()
    }

    private fun enqueueStreamDelta(message: Message?, delta: String?) {
        if (message == null || delta.isNullOrEmpty()) return
        if (streamingTargetMessage !== message) {
            streamingTargetMessage = message
            pendingStreamChars.setLength(0)
        }
        pendingStreamChars.append(delta)
        if (streamTypewriterRunning) return
        streamTypewriterRunning = true
        mainHandler.post(streamTypewriterRunnable)
    }

    private fun stopStreamTypewriter(clearPending: Boolean) {
        mainHandler.removeCallbacks(streamTypewriterRunnable)
        streamTypewriterRunning = false
        if (clearPending) pendingStreamChars.setLength(0)
    }

    private fun drainPendingStreamCharsTo(message: Message?) {
        if (message == null) {
            stopStreamTypewriter(true)
            return
        }
        mainHandler.removeCallbacks(streamTypewriterRunnable)
        streamTypewriterRunning = false
        if (pendingStreamChars.isNotEmpty()) {
            val old = message.content ?: ""
            message.content = old + pendingStreamChars.toString()
            pendingStreamChars.setLength(0)
        }
    }

    private fun renderStreamingMessageTick(message: Message?) {
        if (isFinishing || isDestroyed) return
        var updated = false
        if (message != null) {
            updated = updated or historyAdapter.notifyMessageChanged(message)
            updated = updated or currentAdapter.notifyMessageChanged(message)
        }
        if (!updated) {
            applyMessagesAndTitle()
            return
        }
        maybeAutoScrollOnStreamTick()
    }

    private fun maybeAutoScrollOnStreamTick() {
        val now = System.currentTimeMillis()
        if (now - lastStreamAutoScrollAt < STREAM_AUTO_SCROLL_THROTTLE_MS) return
        lastStreamAutoScrollAt = now
        maybeAutoScrollToBottom(false)
    }

    private fun maybeAutoScrollToBottom(force: Boolean) {
        val scroll = scrollMessagesView ?: return
        if (!force) {
            if (!assistantResponseInProgress) return
            if (!autoScrollToBottomEnabled) return
        }
        scroll.post {
            if (isFinishing || isDestroyed || scrollMessagesView == null) return@post
            val child = scrollMessagesView?.getChildAt(0) ?: return@post
            val y = maxOf(0, child.measuredHeight - scroll.height)
            if (force) {
                scroll.scrollTo(0, y)
            } else {
                scroll.smoothScrollTo(0, y)
            }
            updateAutoScrollStateFromPosition()
            updateLoadEarlierEntryVisibility()
        }
    }

    private fun updateAutoScrollStateFromPosition() {
        val scroll = scrollMessagesView ?: run {
            autoScrollToBottomEnabled = true
            return
        }
        val child = scroll.getChildAt(0) ?: run {
            autoScrollToBottomEnabled = true
            return
        }
        val distanceToBottom = child.bottom - (scroll.scrollY + scroll.height)
        val thresholdPx = (AUTO_SCROLL_BOTTOM_GAP_DP * resources.displayMetrics.density).toInt()
        autoScrollToBottomEnabled = distanceToBottom <= thresholdPx
    }

    private fun beginThinking(message: Message?) {
        if (message == null) return
        if (!message.thinkingRunning) {
            message.thinkingRunning = true
            message.thinkingStartedAt = System.currentTimeMillis()
            message.thinkingElapsedMs = 0L
        }
        activeThinkingMessage = message
        mainHandler.removeCallbacks(thinkingTicker)
        mainHandler.post(thinkingTicker)
    }

    private fun finishThinking(message: Message?) {
        if (message == null || !message.thinkingRunning) return
        message.thinkingElapsedMs = maxOf(0L, System.currentTimeMillis() - message.thinkingStartedAt)
        message.thinkingRunning = false
        if (activeThinkingMessage === message) {
            mainHandler.removeCallbacks(thinkingTicker)
            activeThinkingMessage = null
        }
    }

    private fun findLatestByRole(role: Int): Message? {
        for (i in allMessages.indices.reversed()) {
            val m = allMessages[i]
            if (m != null && m.role == role) return m
        }
        return null
    }

    private fun loadOlderMessages() {
        if (viewModel.isLoadingOlderMessages() || !hasMoreOlderMessages) return
        updateLoadEarlierEntryVisibility()
        viewModel.loadOlderMessages()
    }

    private fun updateLoadEarlierEntryVisibility() {
        val view = loadEarlierMessagesView ?: return
        val atTop = isAtTopForLoadMore()
        val visible = hasMoreOlderMessages && atTop
        view.visibility = if (visible) View.VISIBLE else View.GONE
        view.isEnabled = !viewModel.isLoadingOlderMessages()
        view.text = when {
            viewModel.isLoadingOlderMessages() -> getString(R.string.loading_earlier_messages)
            olderRemainingCount > 0 -> getString(R.string.load_earlier_messages_remaining, olderRemainingCount)
            else -> getString(R.string.load_earlier_messages)
        }
    }

    private fun isAtTopForLoadMore(): Boolean {
        val scroll = scrollMessagesView ?: return true
        val gapPx = (TOP_LOAD_TRIGGER_GAP_DP * resources.displayMetrics.density).toInt()
        return scroll.scrollY <= gapPx
    }

    private fun toAscending(descList: List<Message>?): List<Message> {
        val out = ArrayList<Message>()
        if (descList.isNullOrEmpty()) return out
        out.addAll(descList)
        out.reverse()
        return out
    }
}
