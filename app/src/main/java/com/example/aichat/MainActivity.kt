package com.example.aichat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
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

        // Dynamic glass response: adjust toolbar blur/elevation on scroll
        val glassToolbar = findViewById<LiquidGlassView>(R.id.glassToolbar)
        sessionList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val scrollOffset = rv.computeVerticalScrollOffset()
                val maxScroll = (300 * resources.displayMetrics.density).toInt()
                val fraction = (scrollOffset.toFloat() / maxScroll).coerceIn(0f, 1f)

                // Elevation: 0dp at rest → 4dp scrolled
                glassToolbar.elevation = fraction * 4 * resources.displayMetrics.density

                // Highlight alpha: 0.12 at rest → 0.25 scrolled
                glassToolbar.setHighlightAlpha(0.12f + fraction * 0.13f)
            }
        })

        findViewById<android.view.View>(R.id.headerMyAssistants).setOnClickListener {
            startActivity(Intent(this, MyAssistantsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnMyAssistants).setOnClickListener {
            startActivity(Intent(this, MyAssistantsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnAllConversations).setOnClickListener {
            startActivity(Intent(this, AllConversationsActivity::class.java))
        }

        val recyclerHomeAssistants: RecyclerView = findViewById(R.id.recyclerHomeAssistants)
        recyclerHomeAssistants.layoutManager =
            GridLayoutManager(this, 2, GridLayoutManager.HORIZONTAL, false)
        homeAssistantAdapter = HomeAssistantAdapter()
        homeAssistantAdapter.setOnAssistantClickListener { a ->
            val sessionId = UUID.randomUUID().toString()
            SessionAssistantBindingStore(this).bind(sessionId, a.id)
            val i = Intent(this, ChatSessionActivity::class.java)
            i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId)
            i.putExtra(ChatSessionActivity.EXTRA_ASSISTANT_ID, a.id)
            startActivity(i)
        }
        recyclerHomeAssistants.adapter = homeAssistantAdapter

        val inputEdit: EditText = findViewById(R.id.inputEdit)
        val sendButton: MaterialButton = findViewById(R.id.sendButton)
        sendButton.isEnabled = false
        sendButton.iconTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled)),
            intArrayOf(Color.WHITE, ContextCompat.getColor(this, R.color.ios_section_label))
        )
        inputEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                sendButton.isEnabled = !s.isNullOrBlank()
            }
        })
        sendButton.setOnClickListener { sendAndOpenSession(inputEdit) }

        loadSessions()
        loadAssistants()
    }

    private fun sendAndOpenSession(inputEdit: EditText) {
        val text = inputEdit.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.error_input_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val i = Intent(this, ChatSessionActivity::class.java)
        i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId)
        i.putExtra(ChatSessionActivity.EXTRA_INITIAL_MESSAGE, text)
        startActivity(i)
        inputEdit.setText("")
    }

    private fun loadSessions() {
        executor.execute {
            val list = db.messageDao().getRecentSessions()
            val optionsStore = SessionChatOptionsStore(this)
            val metaStore = SessionMetaStore(this)
            val merged = ArrayList<SessionSummary>()
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
                        if ((s.avatar == null || s.avatar.trim().isEmpty())
                            && opts.sessionAvatar != null && opts.sessionAvatar.trim().isNotEmpty()
                        ) {
                            s.avatar = opts.sessionAvatar.trim()
                        }
                    } else {
                        s.title = shortenTitle(firstUserMessage)
                    }
                    merged.add(s)
                }
            }
            Collections.sort(merged, Comparator
                .comparing { s: SessionSummary -> s.pinned }.reversed()
                .thenComparing({ s: SessionSummary -> s.lastAt }, Comparator.reverseOrder()))
            mainHandler.post { sessionAdapter.setSessions(merged) }
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
