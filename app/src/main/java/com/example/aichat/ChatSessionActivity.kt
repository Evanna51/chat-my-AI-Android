package com.example.aichat

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.MotionEvent
import android.view.ViewParent
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import androidx.core.content.ContextCompat
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
        /** 发送后这段时间内点击 [sendButton] 不会触发 stop, 防误触刚发出去的消息. */
        private const val STOP_GUARD_MS = 1000L
        /**
         * 手指离开屏幕后还要延迟多久才解除 userGesturing — 覆盖 fling 惯性飞行期.
         * 这段时间内流式 chunk 不会触发 auto scroll, 避免在用户 fling 翻历史时被强行拉回底.
         */
        private const val GESTURE_END_DELAY_MS = 600L
    }

    private var sessionId: String = ""
    private lateinit var historyAdapter: MessageAdapter
    private lateinit var currentAdapter: MessageAdapter
    private val assistantMarkdownStateStore = MessageAdapter.AssistantMarkdownStateStore()
    private var sendButtonView: ImageButton? = null
    private var inputEditView: EditText? = null
    private var attachmentsScrollView: HorizontalScrollView? = null
    private var attachmentsContainerView: LinearLayout? = null
    private val pendingAttachments: MutableList<PendingAttachment> = ArrayList()

    private data class PendingAttachment(
        val displayName: String,
        val content: String,
        val truncated: Boolean
    )
    /** 流式开始时间 (elapsedRealtime). 用于 [STOP_GUARD_MS] 防误触检查. */
    private var streamStartedAtMs: Long = 0L
    private lateinit var chatService: ChatService
    private lateinit var viewModel: ChatViewModel
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeThinkingMessage: Message? = null

    private val filePickerLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            handleFilePicked(uri)
        }

    private fun handleFilePicked(uri: Uri) {
        Toast.makeText(this, R.string.attachment_reading_file, Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = AttachmentFileReader.read(this, uri)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                when (result) {
                    is AttachmentFileReader.Result.Text -> {
                        addPendingAttachment(
                            PendingAttachment(result.displayName, result.content, result.truncated)
                        )
                    }
                    is AttachmentFileReader.Result.Unsupported -> {
                        addPendingAttachment(
                            PendingAttachment(result.displayName, "", false)
                        )
                        Toast.makeText(
                            this,
                            getString(R.string.attachment_unsupported_format, result.reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is AttachmentFileReader.Result.Failure -> {
                        Toast.makeText(
                            this,
                            getString(R.string.attachment_read_failed, result.reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private val photoPickerLauncher: ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            insertAttachmentText("\n[图片: $uri]\n")
        }

    private val locationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) fetchLastKnownLocationAndInsert()
            else Toast.makeText(this, R.string.error_location_permission_denied, Toast.LENGTH_SHORT).show()
        }

    private fun handleAddLocationClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fetchLastKnownLocationAndInsert()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun fetchLastKnownLocationAndInsert() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            var best: Location? = null
            for (p in providers) {
                val l = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
                if (l != null && (best == null || l.accuracy < best.accuracy)) best = l
            }
            if (best == null) {
                Toast.makeText(this, R.string.error_location_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            val lat = String.format("%.5f", best.latitude)
            val lng = String.format("%.5f", best.longitude)
            insertAttachmentText("\n[位置: $lat,$lng]\n")
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.error_location_permission_denied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_location_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun insertAttachmentText(text: String) {
        val edit = inputEditView ?: return
        val current = edit.text?.toString() ?: ""
        edit.setText(current + text)
        edit.setSelection(edit.text?.length ?: 0)
        updateSendButtonState()
    }

    private fun addPendingAttachment(attachment: PendingAttachment) {
        pendingAttachments.add(attachment)
        refreshAttachmentBar()
        updateSendButtonState()
    }

    private fun removePendingAttachment(attachment: PendingAttachment) {
        if (pendingAttachments.remove(attachment)) {
            refreshAttachmentBar()
            updateSendButtonState()
        }
    }

    private fun clearPendingAttachments() {
        if (pendingAttachments.isEmpty()) return
        pendingAttachments.clear()
        refreshAttachmentBar()
        updateSendButtonState()
    }

    private fun refreshAttachmentBar() {
        val container = attachmentsContainerView ?: return
        val scroll = attachmentsScrollView ?: return
        container.removeAllViews()
        if (pendingAttachments.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for (att in pendingAttachments) {
            val chip = inflater.inflate(R.layout.item_attachment_chip, container, false)
            val nameView = chip.findViewById<TextView>(R.id.textAttachmentName)
            val removeBtn = chip.findViewById<ImageButton>(R.id.btnAttachmentRemove)
            val suffix = if (att.truncated) getString(R.string.attachment_truncated_suffix) else ""
            nameView.text = att.displayName + suffix
            removeBtn.setOnClickListener { removePendingAttachment(att) }
            container.addView(chip)
        }
    }

    private fun composeMessageWithPendingAttachments(text: String): String {
        if (pendingAttachments.isEmpty()) return text
        val sb = StringBuilder()
        if (text.isNotEmpty()) sb.append(text)
        for (att in pendingAttachments) {
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
            val suffix = if (att.truncated) getString(R.string.attachment_truncated_suffix) else ""
            sb.append("\n[文件: ").append(att.displayName).append(suffix).append("]\n")
            if (att.content.isNotEmpty()) {
                sb.append("```\n").append(att.content).append("\n```\n")
            }
        }
        return sb.toString().trim()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

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

    // Single reusable Runnable — posted via scroll.post() and cancelled via scroll.removeCallbacks().
    // Using a named field (instead of anonymous lambdas) lets removeCallbacks reliably dequeue it,
    // preventing stale scrollTo calls after activity destruction.
    /**
     * 自动滚到底部 — 修复版。两个老 bug：
     *  1) 老版本在 post 后立刻读 child.measuredHeight，那时 TextView 的 requestLayout 还没跑，
     *     拿到 stale 高度 → scrollTo 到旧底部 → 末尾的 updateAutoScrollStateFromPosition 看到
     *     ΔH > 32dp 就把 flag 翻 false，之后再也跟不动了。
     *  2) 程序化 scrollTo 同步触发 setOnScrollChangeListener，原 listener 又会写 flag，导致
     *     跟用户向上拖的手势打架，用户卡在「每拽一下就被拉回底」。
     * 修复：等 layout 完（OneShotPreDrawListener）再读高度；runnable 不再写 flag；listener 用
     * inProgrammaticScroll 闸门跳过我们自己引发的滚动；userGesturing 时直接 bail。
     */
    private val autoScrollRunnable = Runnable {
        val scroll = scrollMessagesView ?: return@Runnable
        if (isFinishing || isDestroyed) return@Runnable
        val forced = pendingAutoScrollForce
        pendingAutoScrollForce = false
        if (!forced) {
            if (userGesturing) return@Runnable
            if (!autoScrollToBottomEnabled) return@Runnable
        }
        androidx.core.view.OneShotPreDrawListener.add(scroll, Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            if (!forced) {
                if (userGesturing) return@Runnable
                if (!autoScrollToBottomEnabled) return@Runnable
            }
            val child = scroll.getChildAt(0) ?: return@Runnable
            val y = maxOf(0, child.measuredHeight - scroll.height)
            if (scroll.scrollY != y) {
                inProgrammaticScroll = true
                try {
                    scroll.scrollTo(0, y)
                } finally {
                    inProgrammaticScroll = false
                }
            }
            // forced（用户发新消息等）显式重置跟随状态，否则不再写 flag — flag 由用户手势独占。
            if (forced) autoScrollToBottomEnabled = true
            updateLoadEarlierEntryVisibility()
        })
    }

    private var historyExpanded = false
    private var addActionsExpanded = false
    private var allMessages: MutableList<Message> = ArrayList()
    private var scrollMessagesView: NestedScrollView? = null
    private var autoScrollToBottomEnabled = true
    /** True 当 autoScrollRunnable 正在做程序化 scrollTo，让 ScrollChangeListener 跳过这次回调。 */
    private var inProgrammaticScroll = false
    /** True 当用户的手指还在 NestedScrollView / RecyclerView 上（DOWN..UP/CANCEL）。 */
    private var userGesturing = false
    /** maybeAutoScrollToBottom(force=true) 时置位，runnable 看到后忽略 flag/手势限制并把 flag 重置为 true。 */
    @Volatile private var pendingAutoScrollForce = false
    private var pendingInitialMessage: String? = null
    private var assistantId: String? = null
    private var writerAssistant = false
    private var characterAssistant = false
    private var autoTtsEnabled = false
    private var btnAutoTtsView: ImageButton? = null
    private val autoReadStore by lazy { AutoReadStore(this) }
    private var characterMemoryService: CharacterMemoryService? = null
    private var outlineStore: SessionOutlineStore? = null
    private var sessionOptions: SessionChatOptions = SessionChatOptions()
    @Volatile private var autoNamingInFlight = false
    private var assistantResponseInProgress = false
    private var streamRenderPending = false
    private var lastStreamRenderAt = 0L
    private var activeChatHandle: ChatService.ChatHandle? = null
    private var activeStreamingMessage: Message? = null
        set(value) {
            field = value
            // 同步给 adapter，让流式消息底部工具栏在生成期间隐藏。
            if (::historyAdapter.isInitialized) historyAdapter.setStreamingAssistantMessage(value)
            if (::currentAdapter.isInitialized) currentAdapter.setStreamingAssistantMessage(value)
        }
    private var activeResponseToken = 0L
    private var lastStreamAutoScrollAt = 0L
    private var streamingTargetMessage: Message? = null
    private val pendingStreamChars = StringBuilder()
    private var streamTypewriterRunning = false
    private var characterMemoryLoadingMessage: Message? = null
    private var loadEarlierMessagesView: TextView? = null
    private var quickModelSwitchView: TextView? = null
    private var firstDialoguePreviewView: TextView? = null
    private var chatTitleView: TextView? = null
    private var expandHistoryView: View? = null
    private var historyExpandIconView: View? = null
    private var hasMoreOlderMessages = false
    private var loadingOlderMessages = false
    private var olderRemainingCount = 0
    private var oldestLoadedCreatedAt = Long.MAX_VALUE
    private var oldestLoadedMessageId = Long.MAX_VALUE

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
            chatTitleView?.text = title
            sessionOptions.sessionTitle = title
            updateToolbarModelSubtitle()
        }
        viewModel.streamDeltaEvent.observe(this) { _ ->
            if (isFinishing || isDestroyed) return@observe
            val events = viewModel.drainPendingStreamEvents()
            for (event in events) {
                if (event.responseToken != activeResponseToken) continue
                handleStreamDeltaEvent(event)
            }
        }
        viewModel.proactiveMessageEvent.observe(this) { event ->
            if (isFinishing || isDestroyed) return@observe
            if (event == null) return@observe
            handleProactiveMessageEvent(event)
        }

        sessionOptions = resolveChatOptions()

        chatTitleView = findViewById(R.id.textChatTitle)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
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
        btnAutoTtsView = findViewById(R.id.btnAutoTts)
        btnAutoTtsView?.setOnClickListener { toggleAutoTts() }
        refreshAutoTtsButton()

        val recyclerHistory: RecyclerView? = findViewById(R.id.recyclerHistory)
        val recyclerCurrent: RecyclerView? = findViewById(R.id.recyclerCurrent)
        val scrollMessages: NestedScrollView? = findViewById(R.id.scrollMessages)
        scrollMessagesView = scrollMessages
        val inputEdit: EditText? = findViewById(R.id.inputEdit)
        val sendButton: ImageButton? = findViewById(R.id.sendButton)
        inputEditView = inputEdit
        sendButtonView = sendButton
        attachmentsScrollView = findViewById(R.id.scrollAttachments)
        attachmentsContainerView = findViewById(R.id.layoutAttachments)
        refreshAttachmentBar()
        inputEdit?.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateSendButtonState() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        val btnAdd: View? = findViewById(R.id.btnAdd)
        val layoutAddActions: View? = findViewById(R.id.layoutAddActions)
        val btnAddFile: View? = findViewById(R.id.btnAddFile)
        val btnAddLocation: View? = findViewById(R.id.btnAddLocation)
        val btnAddPhoto: View? = findViewById(R.id.btnAddPhoto)
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
        historyAdapter.setCharacterMode(characterAssistant)
        currentAdapter.setCharacterMode(characterAssistant)
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
        btnAddFile?.setOnClickListener { filePickerLauncher.launch(arrayOf("*/*")) }
        btnAddLocation?.setOnClickListener { handleAddLocationClicked() }
        btnAddPhoto?.setOnClickListener {
            photoPickerLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
        btnAddMore?.setOnClickListener { Toast.makeText(this, "更多功能 TODO", Toast.LENGTH_SHORT).show() }

        updateSendButtonState()
        sendButton?.setOnClickListener {
            if (assistantResponseInProgress) {
                // 1s 防误触: 刚发出去手指还没离开按钮就识别成第二次点击 → 误触发 stop.
                val sinceStart = android.os.SystemClock.elapsedRealtime() - streamStartedAtMs
                if (streamStartedAtMs > 0 && sinceStart < STOP_GUARD_MS) return@setOnClickListener
                stopLatestResponse()
                return@setOnClickListener
            }
            val rawText = inputEditView?.text?.toString()?.trim() ?: ""
            val composed = composeMessageWithPendingAttachments(rawText)
            if (composed.isEmpty()) return@setOnClickListener
            inputEditView?.setText("")
            clearPendingAttachments()
            sendMessageFromText(composed)
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
            chatTitleView?.text = sessionTitle.trim()
            updateToolbarModelSubtitle()
            return
        }
        val meta = SessionMetaStore(this).get(sessionId)
        if (meta != null && !meta.title.isNullOrEmpty()) {
            chatTitleView?.text = meta.title.trim()
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
        chatTitleView?.text = if (title.isEmpty()) "新对话" else title
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
        if (VolcEngineTTSManager.isPlaying()) VolcEngineTTSManager.stop()
        streamStartedAtMs = android.os.SystemClock.elapsedRealtime()
        setAssistantResponseInProgress(true)
        activeResponseToken = viewModel.incrementResponseToken()
        val responseToken = activeResponseToken
        activeStreamingMessage = null
        activeChatHandle = null

        val userMsg = Message(sessionId, Message.ROLE_USER, text)
        viewModel.insertMessageAsync(userMsg, assistantId)
        // 自动对话: 用户发新消息 → 取消任何 pending 的 follow-up timer
        viewModel.cancelPendingProactive()
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
        val plainApiUserMessage = buildUserMessageForApi(text)
        val finalHistoryForApi = historyForApi
        val finalOptions = options
        // Chapter plan auto-trigger removed; plan is now generated manually from the outline page "更多" menu.
        dispatchChatRequestWithOptionalMemory(finalHistoryForApi, plainApiUserMessage, finalOptions, responseToken, shouldUseCharacterMemory)
    }

    private fun dispatchChatRequestWithOptionalMemory(
        historyForApi: List<Message>,
        plainApiUserMessage: String,
        options: SessionChatOptions,
        responseToken: Long,
        shouldUseCharacterMemory: Boolean
    ) {
        if (!shouldUseCharacterMemory) {
            dispatchChatRequest(historyForApi, plainApiUserMessage, options, responseToken)
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
                dispatchChatRequest(historyForApi, finalUserMessage, options, responseToken)
            }
        }
    }

    private fun dispatchChatRequest(
        historyForApi: List<Message>,
        apiUserMessage: String,
        options: SessionChatOptions,
        responseToken: Long
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
            // 不 force: 用户在底部就跟着, 在中间看历史就别强拽回底.
            maybeAutoScrollToBottom(false)
        }
        activeStreamingMessage = streamingAssistant
        streamingTargetMessage = streamingAssistant
        stopStreamTypewriter(true)
        try {
            activeChatHandle = viewModel.doChatRequest(
                historyForApi, apiUserMessage, options, responseToken,
                assistantId, characterMemoryService!!)
        } catch (e: Exception) {
            setAssistantResponseInProgress(false)
            activeChatHandle = null
            activeStreamingMessage = null
            Toast.makeText(this, getString(R.string.error_send_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun handleStreamDeltaEvent(event: ChatViewModel.StreamDeltaEvent) {
        if (event.toolCallStarted != null) {
            // Reuse the typing placeholder while a tool call (e.g. search_memory)
            // is in flight; the next stream round's first onPartial will clear it.
            val streamingMsg = activeStreamingMessage
            if (streamingMsg != null) {
                stopStreamTypewriter(true)
                streamingMsg.content = CHARACTER_MEMORY_LOADING_TEXT
                streamingMsg.reasoning = ""
                streamingMsg.thinkingRunning = false
                streamingMsg.thinkingElapsedMs = 0L
                applyMessagesAndTitle()
            }
            return
        }
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
                // ViewModel.onSuccess 已 insert 这条 assistant 行, 把真实 id / sync 字段
                // 同步回 streaming, 让后续 persistSessionMessagesAsync 走增量 upsert 时
                // 能命中同一行, 不再 wipe + reinsert 导致 turnId 漂移.
                if (event.assistantInsertedId > 0) {
                    streaming.id = event.assistantInsertedId
                    streaming.turnId = event.assistantTurnId
                    streaming.assistantId = event.assistantAssignedId
                    streaming.synced = 0
                }
            } else {
                val assistantMsg = Message(sessionId, Message.ROLE_ASSISTANT, safeContent)
                if (event.assistantInsertedId > 0) {
                    assistantMsg.id = event.assistantInsertedId
                    assistantMsg.turnId = event.assistantTurnId
                    assistantMsg.assistantId = event.assistantAssignedId
                }
                allMessages.add(assistantMsg)
            }
            flushStreamRenderNow()
            maybeAutoScrollToBottom(shouldStick)
            maybeAutoReadAssistantMessage(streaming, safeContent)
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
            handleResponseStopped(activeStreamingMessage)
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
        chatTitleView?.text = title
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
        chatTitleView?.text = fallbackTitle
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
                if (out.sessionAvatarImageBase64.isNullOrEmpty() && !assistant.avatarImageBase64.isNullOrEmpty()) {
                    out.sessionAvatarImageBase64 = assistant.avatarImageBase64
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

        // 角色没有覆盖某项参数时，再回退到模型默认 — 一次性写入会话以保证后续行为稳定。
        // 老语义：会话保存后是 source of truth；这里只是创建那一刻对空值做个种子填充。
        val modelDefaults = ChatParamsResolver.lookupModelDefaults(this, out.modelKey)
        if (modelDefaults != null) {
            if (out.maxTokens == null) out.maxTokens = modelDefaults.maxTokens
            if (out.frequencyPenalty == null) out.frequencyPenalty = modelDefaults.frequencyPenalty
            if (out.presencePenalty == null) out.presencePenalty = modelDefaults.presencePenalty
            if (out.topK == null) out.topK = modelDefaults.topK
        }
        return out
    }

    private fun copyOptions(src: SessionChatOptions?): SessionChatOptions {
        val out = SessionChatOptions()
        if (src == null) return out
        out.sessionTitle = src.sessionTitle ?: ""
        out.sessionAvatar = src.sessionAvatar ?: ""
        out.sessionAvatarImageBase64 = src.sessionAvatarImageBase64
        out.contextMessageCount = src.contextMessageCount
        out.modelKey = src.modelKey ?: ""
        out.systemPrompt = src.systemPrompt ?: ""
        out.stop = src.stop ?: ""
        out.temperature = src.temperature
        out.topP = src.topP
        out.maxTokens = src.maxTokens
        out.frequencyPenalty = src.frequencyPenalty
        out.presencePenalty = src.presencePenalty
        out.topK = src.topK
        out.streamOutput = true
        out.autoChapterPlan = src.autoChapterPlan
        out.thinking = src.thinking
        out.googleThinkingBudget = src.googleThinkingBudget
        out.autoChatEnabled = src.autoChatEnabled
        out.proactiveDailyBudget = src.proactiveDailyBudget
        return out
    }

    override fun onDestroy() {
        VolcEngineTTSManager.stop()
        // Keep in-flight response alive when leaving page/app.
        // It can still finish in background and be persisted to DB.
        activeChatHandle = null
        activeStreamingMessage = null
        stopStreamTypewriter(true)
        streamingTargetMessage = null
        mainHandler.removeCallbacks(streamRenderRunnable)
        streamRenderPending = false
        mainHandler.removeCallbacks(thinkingTicker)
        scrollMessagesView?.removeCallbacks(autoScrollRunnable)
        activeThinkingMessage = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        writerAssistant = resolveWriterAssistant()
        characterAssistant = resolveCharacterAssistant()
        historyAdapter.setWriterMode(writerAssistant)
        currentAdapter.setWriterMode(writerAssistant)
        historyAdapter.setDisableAssistantCollapseToggle(characterAssistant)
        currentAdapter.setDisableAssistantCollapseToggle(characterAssistant)
        historyAdapter.setCharacterMode(characterAssistant)
        currentAdapter.setCharacterMode(characterAssistant)
        historyAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant)
        currentAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant)
        val btnWriterOutline: View? = findViewById(R.id.btnWriterOutline)
        btnWriterOutline?.visibility = if (writerAssistant) View.VISIBLE else View.GONE
        refreshAutoTtsButton()
        sessionOptions = resolveChatOptions()
        applyMessagesAndTitle()
        // Bootstrap (coreMemories / coreFacts / relationshipState) — fire-and-forget;
        // 跨日才会真正发请求, 同日 no-op. ChatViewModel 拼 prompt 时直接读 in-memory cache.
        if (!assistantId.isNullOrEmpty()) {
            com.example.aichat.sync.CharacterBootstrapStore
                .getInstance(this).refreshIfStale(assistantId)
        }
    }

    private fun updateToolbarModelSubtitle() {
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
        val density = resources.displayMetrics.density
        val labels = listOf(
            getString(R.string.quick_jump_chapters),
            getString(R.string.tool_call_log),
            getString(R.string.character_info),
        )
        val popup = ListPopupWindow(this)
        popup.setAdapter(ArrayAdapter(this, R.layout.item_popup_menu, labels))
        popup.anchorView = anchor
        popup.width = (168 * density + 0.5f).toInt()
        popup.isModal = true
        popup.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.bg_popup_menu))
        // 定位到 toolbar 容器底部下方 8dp，而非紧贴按钮
        val toolbarContainer = anchor.parent as? View
        val extraVertical = if (toolbarContainer != null)
            toolbarContainer.height - (anchor.top + anchor.height) + (8 * density + 0.5f).toInt()
        else (8 * density + 0.5f).toInt()
        popup.verticalOffset = extraVertical
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            when (position) {
                0 -> showChapterJumpDialog()
                1 -> openToolCallLog()
                2 -> openCharacterInfo()
            }
        }
        popup.show()
    }

    private fun openToolCallLog() {
        startActivity(Intent(this, ToolCallLogActivity::class.java)
            .putExtra(ToolCallLogActivity.EXTRA_SESSION_ID, sessionId))
    }

    private fun openCharacterInfo() {
        startActivity(Intent(this, CharacterInfoActivity::class.java)
            .putExtra(CharacterInfoActivity.EXTRA_SESSION_ID, sessionId))
    }

    private fun showChapterJumpDialog() {
        val items = buildChapterJumpItems()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.no_assistant_chapters, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = Array<CharSequence>(items.size) { i ->
            val one = items[i]
            val prefix = "章节${one.index}："   // 黑体部分
            val text = prefix + one.preview
            SpannableString(text).also { s ->
                // 序号前缀：黑体
                s.setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // 整行：字号缩小一档（约 87.5%）
                s.setSpan(RelativeSizeSpan(0.875f), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
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
        // 只取第一个非空行，截断到 40 字
        for (line in content.split(Regex("\\r?\\n"))) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            return if (trimmed.length > 40) trimmed.substring(0, 40) + "…" else trimmed
        }
        val fallback = content.trim()
        return if (fallback.length > 40) fallback.substring(0, 40) + "…" else fallback
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
        viewModel.insertMessageAsync(opening, assistantId)
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
                // proactiveKind != 0 的行 (远程推送 / 仿推送 / split) 不在 persist 对账范围,
                // 只靠 persistSessionMessagesAsync 删不掉 DB; 这里按 id 兜底删一次.
                if (message.id > 0L) viewModel.deleteMessageByIdAsync(message.id)
                persistSessionMessagesAsync()
            }

            override fun onVoicePlay(message: Message) {
                handleVoicePlay(message)
            }
        })
    }

    private fun maybeAutoReadAssistantMessage(message: Message?, content: String) {
        if (!autoTtsEnabled || !characterAssistant) return
        if (message == null || content.isBlank()) return
        handleVoicePlay(message)
    }

    private fun toggleAutoTts() {
        val id = assistantId
        if (id.isNullOrEmpty() || !characterAssistant) return
        autoTtsEnabled = !autoTtsEnabled
        autoReadStore.setEnabled(id, autoTtsEnabled)
        btnAutoTtsView?.alpha = if (autoTtsEnabled) 1.0f else 0.4f
        if (!autoTtsEnabled) VolcEngineTTSManager.stop()
        Toast.makeText(
            this,
            if (autoTtsEnabled) R.string.auto_tts_on else R.string.auto_tts_off,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun refreshAutoTtsButton() {
        val btn = btnAutoTtsView ?: return
        val visible = characterAssistant && !writerAssistant && !assistantId.isNullOrEmpty()
        btn.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            autoTtsEnabled = false
            return
        }
        autoTtsEnabled = autoReadStore.isEnabled(assistantId)
        btn.alpha = if (autoTtsEnabled) 1.0f else 0.4f
    }

    private fun handleVoicePlay(message: Message) {
        val tts = VolcEngineTTSManager
        if (tts.isPlaying() && tts.currentPlayingMessageId() == message.id) {
            tts.stop()
            historyAdapter.updateVoicePlayState(null)
            currentAdapter.updateVoicePlayState(null)
            return
        }
        val raw = message.content?.trim() ?: ""
        val text: String
        val speechParams: VolcEngineHttpTTS.SpeechParams?
        if (characterAssistant) {
            val parsed = EmotionTagParser.parse(raw)
            text = parsed.ttsText
            val profile = parsed.profile
            speechParams = if (profile != null && profile.hasAnyParam()) {
                VolcEngineHttpTTS.SpeechParams(
                    emotion = profile.emotion,
                    emotionScale = profile.emotionScale,
                    speechRate = profile.speechRate,
                    loudnessRate = profile.loudnessRate,
                    pitchRate = profile.pitchRate,
                )
            } else null
        } else {
            text = raw
            speechParams = null
        }
        if (text.isEmpty()) {
            Toast.makeText(this, "消息为空，无法朗读", Toast.LENGTH_SHORT).show()
            return
        }
        val callback = object : VolcEngineTTSManager.TTSCallback {
            override fun onStateChanged(state: VolcEngineTTSManager.State) {
                val playingId = if (state == VolcEngineTTSManager.State.PLAYING ||
                    state == VolcEngineTTSManager.State.LOADING
                ) message.id else null
                historyAdapter.updateVoicePlayState(playingId)
                currentAdapter.updateVoicePlayState(playingId)
            }

            override fun onError(message: String) {
                Toast.makeText(this@ChatSessionActivity, message, Toast.LENGTH_SHORT).show()
                historyAdapter.updateVoicePlayState(null)
                currentAdapter.updateVoicePlayState(null)
            }
        }
        tts.speak(text, message.id, callback, speechParams)
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

    private fun buildUserMessageForApi(text: String): String {
        val source = text.trim()
        if (!writerAssistant || source.isEmpty()) return source
        val outlines = outlineStore?.getAll(sessionId).orEmpty()
        val outlineBlock = OutlinePromptBuilder.build(outlines, includeKnowledgeEnforcement = true)
        if (outlineBlock.isEmpty()) return source
        return buildString {
            append(source).append("\n\n")
            append("【写作大纲与资料】\n")
            append(outlineBlock).append("\n\n")
            append("请严格参考以上内容，保持情节、设定、任务线索的一致性与准确性。")
        }.trim()
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

    /**
     * memory-context 注入路径暂时弃用 (2026-05-07).
     * 现在由 LLM 通过 search_memory tool 按需主动检索, 不再每条 user message 自动 prepend.
     * 函数 / 接口 / DTO 全部保留, 后续若想恢复, 把 return false 换回原条件即可.
     */
    private fun shouldUseCharacterMemory(): Boolean {
        return false
        // 原条件:
        // return characterAssistant
        //         && !assistantId.isNullOrEmpty()
        //         && characterMemoryService != null
        //         && characterMemoryService!!.isEnabled()
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
        // 不 force: AI loading 占位也不该抢用户阅读位置.
        maybeAutoScrollToBottom(false)
    }

    private fun removeCharacterMemoryLoadingPlaceholder() {
        val loading = characterMemoryLoadingMessage ?: return
        allMessages.remove(loading)
        characterMemoryLoadingMessage = null
        applyMessagesAndTitle()
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
        val htmlBody = markdownToHtml(content.trim())
        return "<!doctype html><html><head><meta charset=\"utf-8\"/>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
                "<title>Message</title>" +
                "<style>" +
                "body{font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:16px 18px;line-height:1.7;font-size:17px;color:#111;max-width:800px;margin:0 auto;}" +
                "h1,h2,h3,h4,h5,h6{margin:1.1em 0 0.4em;line-height:1.3;}" +
                "p{margin:0.7em 0;}" +
                "pre{background:#f4f4f4;padding:12px 14px;border-radius:8px;overflow-x:auto;margin:0.8em 0;}" +
                "code{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:.9em;background:#f0f0f0;padding:2px 5px;border-radius:4px;}" +
                "pre code{background:none;padding:0;}" +
                "ul,ol{padding-left:1.6em;margin:.5em 0;}" +
                "li{margin:3px 0;}" +
                "blockquote{border-left:3px solid #ccc;margin:.8em 0;padding:4px 12px;color:#555;}" +
                "hr{border:none;border-top:1px solid #ddd;margin:1.2em 0;}" +
                "a{color:#007aff;}" +
                "@media(prefers-color-scheme:dark){body{color:#e8e8e8;background:#1a1a1a;}pre{background:#2b2b2b;}code{background:#2b2b2b;}blockquote{color:#aaa;border-left-color:#555;}hr{border-top-color:#444;}a{color:#0a84ff;}}" +
                "</style></head><body>$htmlBody</body></html>"
    }

    private fun markdownToHtml(md: String): String {
        val sb = StringBuilder()
        val lines = md.lines()
        var i = 0
        var inUl = false
        var inOl = false

        fun closeUl() { if (inUl) { sb.append("</ul>\n"); inUl = false } }
        fun closeOl() { if (inOl) { sb.append("</ol>\n"); inOl = false } }
        fun closeLists() { closeUl(); closeOl() }

        while (i < lines.size) {
            val line = lines[i].trimStart()

            // Fenced code block
            if (line.startsWith("```") || line.startsWith("~~~")) {
                closeLists()
                val fence = if (line.startsWith("```")) "```" else "~~~"
                val lang = line.removePrefix(fence).trim()
                sb.append(if (lang.isNotEmpty()) "<pre><code class=\"language-${escapeHtml(lang)}\">" else "<pre><code>")
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    sb.append(escapeHtml(lines[i])).append("\n")
                    i++
                }
                sb.append("</code></pre>\n")
                i++ // skip closing fence
                continue
            }

            // Blank line
            if (line.isEmpty()) {
                closeLists()
                i++; continue
            }

            // ATX Headings
            val hMatch = Regex("^(#{1,6})\\s+(.+?)\\s*#*$").matchEntire(line)
            if (hMatch != null) {
                closeLists()
                val level = hMatch.groupValues[1].length
                sb.append("<h$level>${inlineMarkdown(hMatch.groupValues[2])}</h$level>\n")
                i++; continue
            }

            // Horizontal rule
            if (line.matches(Regex("-{3,}|\\*{3,}|_{3,}"))) {
                closeLists()
                sb.append("<hr>\n")
                i++; continue
            }

            // Blockquote
            if (line.startsWith("> ")) {
                closeLists()
                sb.append("<blockquote><p>${inlineMarkdown(line.removePrefix("> "))}</p></blockquote>\n")
                i++; continue
            }

            // Unordered list item
            val ulMatch = Regex("^[-*+]\\s+(.+)").matchEntire(line)
            if (ulMatch != null) {
                closeOl()
                if (!inUl) { sb.append("<ul>\n"); inUl = true }
                sb.append("<li>${inlineMarkdown(ulMatch.groupValues[1])}</li>\n")
                i++; continue
            }

            // Ordered list item
            val olMatch = Regex("^\\d+\\.\\s+(.+)").matchEntire(line)
            if (olMatch != null) {
                closeUl()
                if (!inOl) { sb.append("<ol>\n"); inOl = true }
                sb.append("<li>${inlineMarkdown(olMatch.groupValues[1])}</li>\n")
                i++; continue
            }

            // Regular paragraph
            closeLists()
            sb.append("<p>${inlineMarkdown(line)}</p>\n")
            i++
        }

        closeLists()
        return sb.toString()
    }

    private fun inlineMarkdown(text: String): String {
        // Escape HTML first, then apply inline Markdown patterns
        var s = escapeHtml(text)
        // Protect inline code from further processing
        val codeBlocks = mutableListOf<String>()
        s = Regex("`([^`]+)`").replace(s) { mr ->
            val idx = codeBlocks.size
            codeBlocks.add("<code>${mr.groupValues[1]}</code>")
            "\u0000CODE$idx\u0000"
        }
        // Bold + italic
        s = Regex("\\*{3}(.+?)\\*{3}").replace(s, "<strong><em>$1</em></strong>")
        // Bold
        s = Regex("\\*{2}(.+?)\\*{2}").replace(s, "<strong>$1</strong>")
        s = Regex("__(.+?)__").replace(s, "<strong>$1</strong>")
        // Italic
        s = Regex("\\*([^*\n]+)\\*").replace(s, "<em>$1</em>")
        s = Regex("_([^_\n]+)_").replace(s, "<em>$1</em>")
        // Links
        s = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").replace(s, "<a href=\"$2\">$1</a>")
        // Restore inline code
        codeBlocks.forEachIndexed { idx, code -> s = s.replace("\u0000CODE$idx\u0000", code) }
        return s
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
        // 手势结束延迟 runnable: ACTION_UP 后过 GESTURE_END_DELAY_MS 才真正解除 userGesturing,
        // 期间认为用户还在 fling, 流式 auto scroll 不抢. ACTION_DOWN 立即取消挂起的解除.
        // 关键: 这里**不再**调 updateAutoScrollStateFromPosition() — 否则会用最后一次 scroll
        // 位置重置 flag, 把"用户离开底部 → disengage"的状态又翻回 engage. 让 flag 保持
        // 手势期间最后一次 ScrollChangeListener 写入的值, 直到用户主动滚回底部.
        val gestureEndRunnable = Runnable {
            userGesturing = false
        }
        // 一个统一的触摸 listener：跟踪用户手势状态 + 顺便折叠消息操作栏。
        val touchHandler = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(gestureEndRunnable)
                    userGesturing = true
                    collapseMessageActions()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 不立即翻 false: 手指离开后还有 fling 惯性, 此时流式 chunk 不该强抢滚动.
                    mainHandler.removeCallbacks(gestureEndRunnable)
                    mainHandler.postDelayed(gestureEndRunnable, GESTURE_END_DELAY_MS)
                }
            }
            false
        }
        if (recyclerHistory != null) {
            recyclerHistory.setOnTouchListener(touchHandler)
            recyclerHistory.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0 || dx != 0) collapseMessageActions()
                }
            })
        }
        if (recyclerCurrent != null) {
            recyclerCurrent.setOnTouchListener(touchHandler)
            recyclerCurrent.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0 || dx != 0) collapseMessageActions()
                }
            })
        }
        scrollMessages?.setOnTouchListener(touchHandler)
        scrollMessages?.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, scrollX, scrollY, oldScrollX, oldScrollY ->
            // 程序化滚动（autoScrollRunnable 内的 scrollTo）同步触发本回调，要忽略掉，
            // 否则我们刚把用户拽回底部 → flag 又被写成 true，下一帧继续拽 → 死循环。
            if (!inProgrammaticScroll) {
                updateAutoScrollStateFromPosition()
            }
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
        if (assistantResponseInProgress) {
            btn.setImageResource(R.drawable.ic_action_stop)
            btn.isEnabled = true
        } else {
            btn.setImageResource(R.drawable.ic_arrow_up)
            val hasText = !inputEditView?.text?.toString()?.trim().isNullOrEmpty()
            btn.isEnabled = hasText || pendingAttachments.isNotEmpty()
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
        handleResponseStopped(target)
    }

    private fun handleResponseStopped(streamingMessage: Message?) {
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
            if (userGesturing) return  // 用户正在拖，绝不强行抢回
        }
        if (force) pendingAutoScrollForce = true
        scroll.removeCallbacks(autoScrollRunnable)
        scroll.post(autoScrollRunnable)
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
        // 一律严格阈值 (4dp ≈ 几乎贴底). 简单一致的策略:
        //   贴底 → engage (auto scroll 跟随)
        //   离开底部 → disengage (auto scroll 不抢)
        // 老 32dp 阈值的"灰色地带"是 bug 来源 — 用户拖了 < 32dp 时 flag 维持 engage,
        // 流式 chunk 持续抢回, 用户感觉"滚不下去". 4dp 给一点 fling 误差余量, 不会震荡.
        val thresholdPx = (4 * resources.displayMetrics.density).toInt()
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

    /**
     * Handle a fine-grained 自动对话 update emitted by [ProactiveChatPlanner].
     *
     * REPLACE: split[0] in-place rewrite — find the row by id and overwrite content.
     * APPEND: split[1..N] / follow-up — append to the bottom.
     *
     * Both code paths avoid the heavy `loadMessages → DB re-read → full LiveData repost`
     * loop used in V1.
     */
    private fun handleProactiveMessageEvent(event: ChatViewModel.ProactiveMessageEvent) {
        when (event.kind) {
            ChatViewModel.ProactiveMessageEvent.KIND_REPLACE -> {
                val target = allMessages.firstOrNull { it.id == event.rowId } ?: return
                target.content = event.newContent
                applyMessagesAndTitle()
                // 不 force: 自动对话 split rewrite 不该抢用户阅读位置.
                maybeAutoScrollToBottom(false)
            }
            ChatViewModel.ProactiveMessageEvent.KIND_APPEND -> {
                val msg = event.appendedMessage ?: return
                allMessages.add(msg)
                applyMessagesAndTitle()
                // 不 force: AI follow-up 主动消息不该抢用户阅读位置.
                maybeAutoScrollToBottom(false)
            }
        }
    }
}
