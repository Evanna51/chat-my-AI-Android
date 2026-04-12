package com.example.aichat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
        sessionAdapter.setSessionActionListener(object : SessionListAdapter.SessionActionListener {
            override fun onHide(s: SessionSummary) {
                if (s.sessionId == null) return
                executor.execute {
                    SessionMetaStore(this@MainActivity).setHidden(s.sessionId, true)
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, R.string.conversation_hidden, Toast.LENGTH_SHORT).show()
                        loadSessions()
                    }
                }
            }

            override fun onDelete(s: SessionSummary) {
                if (s.sessionId == null) return
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.delete_conversation)
                    .setMessage(R.string.delete_conversation_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        executor.execute {
                            db.messageDao().deleteBySession(s.sessionId)
                            SessionMetaStore(this@MainActivity).remove(s.sessionId)
                            SessionChatOptionsStore(this@MainActivity).remove(s.sessionId)
                            SessionAssistantBindingStore(this@MainActivity).remove(s.sessionId)
                            mainHandler.post {
                                Toast.makeText(this@MainActivity, R.string.conversation_deleted, Toast.LENGTH_SHORT).show()
                                loadSessions()
                            }
                        }
                    }
                    .show()
            }
        })
        sessionList.adapter = sessionAdapter

        findViewById<android.view.View>(R.id.headerMyAssistants).setOnClickListener {
            startActivity(Intent(this, MyAssistantsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnAllConversations).setOnClickListener {
            startActivity(Intent(this, AllConversationsActivity::class.java))
        }

        val recyclerHomeAssistants: RecyclerView = findViewById(R.id.recyclerHomeAssistants)
        recyclerHomeAssistants.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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
            val list = MyAssistantStore(this).getRecent(5)
            mainHandler.post {
                homeAssistantAdapter.setItems(list)
            }
        }
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
