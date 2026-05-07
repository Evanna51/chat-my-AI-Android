package com.example.aichat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.ArrayList
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ThemedActivity() {

    companion object {
        private const val PREFS_RUNTIME = "aichat_runtime"
        private const val KEY_NOTIF_PERMISSION_REQUESTED = "notif_permission_requested"
    }

    private lateinit var sessionAdapter: SessionListAdapter
    private lateinit var homeAssistantAdapter: HomeAssistantAdapter
    private lateinit var db: AppDatabase
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentTab: HomeTab = HomeTab.RECENT
    private val tabViews: MutableMap<HomeTab, TextView> = HashMap()
    private var tabIndicator: View? = null
    private var assistantsExpanded: Boolean = false
    private lateinit var recyclerHomeAssistants: RecyclerView

    private enum class HomeTab { RECENT, PINNED, NOVEL, ALL }
    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // No-op: app can continue without notification permission.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getInstance(this)

        val btnSettings: ImageButton = findViewById(R.id.btnSettings)
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        setupTabs()
        setupSearchToggle()

        val sessionList: RecyclerView = findViewById(R.id.sessionList)
        sessionList.layoutManager = LinearLayoutManager(this)
        sessionAdapter = SessionListAdapter()
        sessionAdapter.setOnSessionClickListener { s ->
            val i = Intent(this, ChatSessionActivity::class.java)
            i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, s.sessionId)
            startActivity(i)
        }
        // Long-press action sheet (pin / hide / delete)
        sessionAdapter.setSessionLongPressListener { session, _ ->
            val pinLabel = if (session.pinned) getString(R.string.unpin) else getString(R.string.pin)
            val items = arrayOf(pinLabel, getString(R.string.hide), getString(R.string.delete_conversation))
            MaterialAlertDialogBuilder(this@MainActivity)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> { // Pin / Unpin
                            executor.execute {
                                SessionMetaStore(this@MainActivity).setPinned(session.sessionId, !session.pinned)
                                mainHandler.post { loadSessions() }
                            }
                        }
                        1 -> { // Hide
                            executor.execute {
                                SessionMetaStore(this@MainActivity).setHidden(session.sessionId, true)
                                mainHandler.post {
                                    Toast.makeText(this@MainActivity, R.string.conversation_hidden, Toast.LENGTH_SHORT).show()
                                    loadSessions()
                                }
                            }
                        }
                        2 -> { // Delete
                            showDeleteConfirmation(session)
                        }
                    }
                }
                .show()
        }
        sessionList.adapter = sessionAdapter

        // Swipe-to-action: left swipe = delete (with confirmation)
        val swipeHelper = SessionSwipeHelper(
            context = this,
            onPin = { pos ->
                val session = sessionAdapter.getSessionAt(pos)
                executor.execute {
                    SessionMetaStore(this).setPinned(session.sessionId, !session.pinned)
                    mainHandler.post { loadSessions() }
                }
            },
            onHide = { pos ->
                val session = sessionAdapter.getSessionAt(pos)
                executor.execute {
                    SessionMetaStore(this).setHidden(session.sessionId, true)
                    mainHandler.post {
                        Toast.makeText(this, R.string.conversation_hidden, Toast.LENGTH_SHORT).show()
                        loadSessions()
                    }
                }
            },
            onDelete = { pos ->
                val session = sessionAdapter.getSessionAt(pos)
                // Reset the swiped item first, then show confirmation dialog
                sessionAdapter.notifyItemChanged(pos)
                showDeleteConfirmation(session)
            }
        )
        ItemTouchHelper(swipeHelper).attachToRecyclerView(sessionList)

        findViewById<View>(R.id.btnViewAllAssistants).setOnClickListener {
            startActivity(Intent(this, MyAssistantsActivity::class.java))
        }

        recyclerHomeAssistants = findViewById(R.id.recyclerHomeAssistants)
        homeAssistantAdapter = HomeAssistantAdapter()
        homeAssistantAdapter.setOnAssistantClickListener { a ->
            val sessionId = UUID.randomUUID().toString()
            SessionAssistantBindingStore(this).bind(sessionId, a.id)
            val i = Intent(this, ChatSessionActivity::class.java)
            i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId)
            i.putExtra(ChatSessionActivity.EXTRA_ASSISTANT_ID, a.id)
            startActivity(i)
        }
        homeAssistantAdapter.setOnMoreClickListener { toggleAssistantsExpanded() }
        recyclerHomeAssistants.adapter = homeAssistantAdapter
        applyAssistantsLayout()

        val btnNewChat: MaterialButton = findViewById(R.id.btnNewChat)
        btnNewChat.setOnClickListener { startNewBlankChat() }

        loadSessions()
        loadAssistants()
        setupSemanticSearch()
    }

    private fun setupSemanticSearch() {
        val searchEdit = findViewById<android.widget.EditText>(R.id.searchEdit) ?: return
        searchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val q = searchEdit.text?.toString()?.trim() ?: ""
                if (q.isEmpty()) {
                    loadSessions()
                } else {
                    runSemanticSearch(q)
                }
                true
            } else false
        }
    }

    /**
     * 搜索: 立即用 SQL LIKE 关键词匹配返回结果, 不阻塞用户. 同时后台跑 embedding
     * 索引 (fire-and-forget), 不影响本次搜索, 仅给以后的语义搜索铺路.
     */
    private fun runSemanticSearch(query: String) {
        val service = SemanticSearchService(this)
        executor.execute {
            // 1. 立即关键词搜, UI 第一时间出结果
            val hits = try {
                service.searchByKeyword(query, 20)
            } catch (e: Exception) {
                emptyList()
            }
            mainHandler.post { applySemanticHits(hits) }

            // 2. 后台机会性索引: 模型配齐了才跑, 没配也不报错
            if (service.resolveEmbeddingModel() != null) {
                try {
                    service.indexPendingMessages(50)
                } catch (_: Exception) {}
            }
        }
    }

    private fun applySemanticHits(hits: List<SemanticSearchService.SemanticHit>) {
        if (hits.isEmpty()) {
            Toast.makeText(this, "无匹配结果", Toast.LENGTH_SHORT).show()
            return
        }
        executor.execute {
            val all = db.messageDao().getRecentSessions() ?: emptyList()
            val byId = all.associateBy { it.sessionId }
            val ordered = ArrayList<SessionSummary>()
            val optionsStore = SessionChatOptionsStore(this)
            val metaStore = SessionMetaStore(this)
            for (h in hits) {
                val s = byId[h.sessionId] ?: continue
                val opts = optionsStore.get(s.sessionId)
                val meta = metaStore.get(s.sessionId)
                if (meta != null) {
                    s.favorite = meta.favorite
                    s.pinned = meta.pinned
                    s.hidden = meta.hidden
                    s.category = if (meta.category != null && meta.category.trim().isNotEmpty()) meta.category.trim() else "默认"
                    if (meta.avatar != null && meta.avatar.trim().isNotEmpty()) s.avatar = meta.avatar.trim()
                }
                if (opts != null && opts.sessionTitle != null && opts.sessionTitle.trim().isNotEmpty()) {
                    s.title = opts.sessionTitle.trim()
                } else if (s.title.isNullOrBlank()) {
                    s.title = h.sampleMessageContent.take(20)
                }
                ordered.add(s)
            }
            mainHandler.post { sessionAdapter.setSessions(ordered) }
        }
    }

    private fun startNewBlankChat() {
        val sessionId = UUID.randomUUID().toString()
        val i = Intent(this, ChatSessionActivity::class.java)
        i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId)
        startActivity(i)
    }

    private fun setupTabs() {
        tabViews[HomeTab.RECENT] = findViewById(R.id.tabRecent)
        tabViews[HomeTab.PINNED] = findViewById(R.id.tabPinned)
        tabViews[HomeTab.NOVEL] = findViewById(R.id.tabNovel)
        tabViews[HomeTab.ALL] = findViewById(R.id.tabAll)
        tabIndicator = findViewById(R.id.tabIndicator)
        tabViews.forEach { (tab, view) ->
            view.setOnClickListener { selectTab(tab) }
        }
        applyTabSelection()
        // Position the indicator after the tab views are measured.
        tabViews[currentTab]?.post { positionIndicator(currentTab, animate = false) }
    }

    private fun selectTab(tab: HomeTab) {
        if (currentTab == tab) return
        currentTab = tab
        applyTabSelection()
        positionIndicator(tab, animate = true)
        loadSessions()
    }

    private fun applyTabSelection() {
        tabViews.forEach { (tab, view) ->
            view.isSelected = tab == currentTab
        }
    }

    private fun positionIndicator(tab: HomeTab, animate: Boolean) {
        val indicator = tabIndicator ?: return
        val target = tabViews[tab] ?: return
        if (target.width == 0) {
            target.post { positionIndicator(tab, animate) }
            return
        }
        val params = indicator.layoutParams
        if (params.width != target.width) {
            params.width = target.width
            indicator.layoutParams = params
        }
        val targetX = target.left.toFloat()
        if (animate) {
            indicator.animate()
                .translationX(targetX)
                .setDuration(260)
                .setInterpolator(android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f))
                .start()
        } else {
            indicator.translationX = targetX
        }
    }

    private fun toggleAssistantsExpanded() {
        assistantsExpanded = !assistantsExpanded
        applyAssistantsLayout()
    }

    private fun applyAssistantsLayout() {
        val density = resources.displayMetrics.density
        val params = recyclerHomeAssistants.layoutParams
        if (assistantsExpanded) {
            recyclerHomeAssistants.layoutManager = GridLayoutManager(this, 4)
            params.height = (300 * density).toInt()
            homeAssistantAdapter.setMoreLabelRes(R.string.home_assistant_collapse)
        } else {
            recyclerHomeAssistants.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            params.height = (124 * density).toInt()
            homeAssistantAdapter.setMoreLabelRes(R.string.home_assistant_more)
        }
        recyclerHomeAssistants.layoutParams = params
    }

    private fun setupSearchToggle() {
        val btnSearch = findViewById<ImageButton>(R.id.btnSearch)
        val glassToolbar = findViewById<View>(R.id.glassToolbar)
        val searchEdit = findViewById<android.widget.EditText>(R.id.searchEdit)
        btnSearch.setOnClickListener {
            if (glassToolbar.visibility == View.VISIBLE) {
                glassToolbar.visibility = View.GONE
                searchEdit?.setText("")
                hideSoftInput(searchEdit)
                loadSessions()
            } else {
                glassToolbar.visibility = View.VISIBLE
                searchEdit?.let { edit ->
                    // toolbar 刚 visible 时 EditText 还没完成 layout, 直接 requestFocus
                    // 弹键盘可能失败 — post 到下一帧再调.
                    edit.post {
                        edit.requestFocus()
                        showSoftInput(edit)
                    }
                }
            }
        }
    }

    private fun showSoftInput(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSoftInput(view: View?) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = view?.windowToken ?: window?.decorView?.windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun loadSessions() {
        executor.execute {
            val list = db.messageDao().getRecentSessions()
            val optionsStore = SessionChatOptionsStore(this)
            val metaStore = SessionMetaStore(this)
            val bindingStore = SessionAssistantBindingStore(this)
            // 一次性把所有助手缓存下来，按 id 索引，便于会话头像继承。
            val assistantById = HashMap<String, MyAssistant>()
            val assistantTypeById = HashMap<String, String>()
            for (a in MyAssistantStore(this).getAll()) {
                if (a.id.isNotEmpty()) {
                    assistantById[a.id] = a
                    assistantTypeById[a.id] = a.type
                }
            }

            val tab = currentTab
            val items = ArrayList<SessionSummary>()
            if (list != null) {
                for (s in list) {
                    if (s == null || s.sessionId == null) continue
                    val opts = optionsStore.get(s.sessionId)
                    val meta = metaStore.get(s.sessionId)
                    val firstUserMessage = s.title ?: ""
                    if (meta != null) {
                        s.favorite = meta.favorite
                        s.pinned = meta.pinned
                        s.hidden = meta.hidden
                        s.category = if (meta.category != null && meta.category.trim().isNotEmpty())
                            meta.category.trim() else "默认"
                        if (meta.avatar != null && meta.avatar.trim().isNotEmpty()) {
                            s.avatar = meta.avatar.trim()
                        }
                    }
                    if (s.hidden) continue
                    if (opts != null) {
                        s.title = if (opts.sessionTitle != null && opts.sessionTitle.trim().isNotEmpty())
                            opts.sessionTitle.trim()
                        else shortenTitle(firstUserMessage)
                        // 注意：opts.sessionAvatar 仅作为遗留字段存在，不再优先使用。
                    } else {
                        s.title = shortenTitle(firstUserMessage)
                    }

                    val assistantId = bindingStore.getAssistantId(s.sessionId)
                    val assistant = assistantById[assistantId]
                    // 头像优先级（高 → 低）：
                    //   1. 会话级覆盖图片 opts.sessionAvatarImageBase64
                    //   2. 会话级覆盖文字 opts.sessionAvatar
                    //   3. meta.avatar（已在前面写入 s.avatar 了）
                    //   4. 助手图片 / 助手 emoji
                    //   5. 默认 🤖
                    val sessionImageOverride = opts?.sessionAvatarImageBase64.orEmpty()
                    val sessionTextOverride = opts?.sessionAvatar.orEmpty().trim()
                    when {
                        sessionImageOverride.isNotBlank() -> {
                            s.avatarImageBase64 = sessionImageOverride
                            // 文字字段也清掉，避免回退时显示一个不一致的 emoji
                            s.avatar = ""
                        }
                        sessionTextOverride.isNotEmpty() -> {
                            s.avatar = sessionTextOverride
                        }
                        s.avatar.isBlank() && assistant != null -> {
                            // 没有会话级覆盖也没有 meta 覆盖，回退到助手
                            if (assistant.avatarImageBase64.isNotBlank()) {
                                s.avatarImageBase64 = assistant.avatarImageBase64
                            }
                            if (assistant.avatar.isNotBlank()) {
                                s.avatar = assistant.avatar
                            }
                        }
                    }
                    val type = assistantTypeById[assistantId] ?: ""
                    val isWriter = type == "writer"

                    val keep = when (tab) {
                        HomeTab.RECENT -> !isWriter
                        HomeTab.PINNED -> s.pinned
                        HomeTab.NOVEL -> isWriter
                        HomeTab.ALL -> true
                    }
                    if (keep) items.add(s)
                }
            }

            val sessionSorter = Comparator
                .comparing { s: SessionSummary -> s.pinned }.reversed()
                .thenComparing({ s: SessionSummary -> s.lastAt }, Comparator.reverseOrder())
            Collections.sort(items, sessionSorter)

            val rows = ArrayList<SessionListAdapter.Row>()
            for (s in items) rows.add(SessionListAdapter.Row.Session(s))
            mainHandler.post {
                sessionAdapter.setRows(rows)
                findViewById<View>(R.id.emptyView)?.visibility =
                    if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadAssistants() {
        executor.execute {
            val list = MyAssistantStore(this).getAll()
            mainHandler.post {
                homeAssistantAdapter.setItems(list)
            }
        }
    }

    private fun showDeleteConfirmation(session: SessionSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_conversation)
            .setMessage(R.string.delete_conversation_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                executor.execute {
                    db.messageDao().deleteBySession(session.sessionId)
                    SessionMetaStore(this).remove(session.sessionId)
                    SessionChatOptionsStore(this).remove(session.sessionId)
                    SessionAssistantBindingStore(this).remove(session.sessionId)
                    mainHandler.post {
                        Toast.makeText(this, R.string.conversation_deleted, Toast.LENGTH_SHORT).show()
                        loadSessions()
                    }
                }
            }
            .show()
    }

    private fun shortenTitle(text: String?): String {
        val source = text?.trim() ?: ""
        if (source.isEmpty()) return "新对话"
        return if (source.length > 15) source.substring(0, 15) else source
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ensureNotificationPermissionIfNeeded()
        loadSessions()
        loadAssistants()
        val action = intent?.getStringExtra("action")
        if ("new_chat" == action) {
            intent.removeExtra("action")
            startNewBlankChat()
        } else if ("export" == action) {
            intent.removeExtra("action")
            Toast.makeText(this, "请进入某个对话后，点击右上角菜单导出", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val asked = getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE)
            .getBoolean(KEY_NOTIF_PERMISSION_REQUESTED, false)
        if (asked) return
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).edit()
            .putBoolean(KEY_NOTIF_PERMISSION_REQUESTED, true)
            .apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
