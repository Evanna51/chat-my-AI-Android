package com.example.aichat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors

class AllConversationsActivity : ThemedActivity() {
    private lateinit var db: AppDatabase
    private lateinit var metaStore: SessionMetaStore
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val collapsedMap: MutableMap<String, Boolean> = LinkedHashMap()
    private lateinit var adapter: AllConversationsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_conversations)
        db = AppDatabase.getInstance(this)
        metaStore = SessionMetaStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recyclerAllConversations)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = AllConversationsAdapter()
        adapter.setActionListener(object : AllConversationsAdapter.ActionListener {
            override fun onOpen(session: SessionSummary) {
                val i = Intent(this@AllConversationsActivity, ChatSessionActivity::class.java)
                i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, session.sessionId)
                startActivity(i)
            }

            override fun onToggleCategory(category: String) {
                val collapsed = collapsedMap.containsKey(category) && collapsedMap[category] == true
                collapsedMap[category] = !collapsed
                loadRows()
            }

            override fun onSetCategory(session: SessionSummary) {
                val categories = metaStore.getAllCategories().toMutableList()
                val customLabel = getString(R.string.category_custom_ellipsis)
                categories.add(customLabel)
                val items = categories.toTypedArray()
                MaterialAlertDialogBuilder(this@AllConversationsActivity)
                    .setTitle(R.string.select_category_title)
                    .setItems(items) { _, which ->
                        val category = items[which]
                        if (customLabel == category) {
                            showCustomCategoryDialog(session)
                            return@setItems
                        }
                        applyCategory(session, category)
                    }
                    .show()
            }

            override fun onGenerateOutline(session: SessionSummary) {
                if (session.sessionId == null) return
                Toast.makeText(this@AllConversationsActivity, R.string.generating_outline, Toast.LENGTH_SHORT).show()
                executor.execute {
                    val full = db.messageDao().getBySession(session.sessionId)
                    val firstTen = mutableListOf<Message>()
                    if (full != null) {
                        var i = 0
                        while (i < full.size && i < 10) {
                            val m = full[i]
                            if (m != null) firstTen.add(m)
                            i++
                        }
                    }
                    ChatService(this@AllConversationsActivity).generateSessionOutline(firstTen, object : ChatService.ChatCallback {
                        override fun onSuccess(content: String) {
                            val outline = content?.trim() ?: ""
                            if (outline.isEmpty()) {
                                onError("生成大纲为空")
                                return
                            }
                            val meta = metaStore.get(session.sessionId)
                            meta.outline = outline
                            metaStore.save(session.sessionId, meta)
                            mainHandler.post {
                                Toast.makeText(this@AllConversationsActivity, R.string.outline_updated, Toast.LENGTH_SHORT).show()
                                loadRows()
                            }
                        }

                        override fun onError(message: String) {
                            mainHandler.post {
                                Toast.makeText(
                                    this@AllConversationsActivity,
                                    if (message != null && message.trim().isNotEmpty()) message else getString(R.string.error_generate_outline_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    })
                }
            }

            override fun onTogglePin(session: SessionSummary) {
                val meta = metaStore.get(session.sessionId)
                meta.pinned = !meta.pinned
                metaStore.save(session.sessionId, meta)
                loadRows()
            }

            override fun onToggleFavorite(session: SessionSummary) {
                val meta = metaStore.get(session.sessionId)
                meta.favorite = !meta.favorite
                if (meta.favorite && (meta.category == null || meta.category.trim().isEmpty() || "默认" == meta.category)) {
                    meta.category = "收藏"
                }
                metaStore.save(session.sessionId, meta)
                loadRows()
            }

            override fun onDelete(session: SessionSummary) {
                if (session.sessionId == null) return
                MaterialAlertDialogBuilder(this@AllConversationsActivity)
                    .setTitle(R.string.delete_conversation)
                    .setMessage(R.string.delete_conversation_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        executor.execute {
                            db.messageDao().deleteBySession(session.sessionId)
                            metaStore.remove(session.sessionId)
                            SessionChatOptionsStore(this@AllConversationsActivity).remove(session.sessionId)
                            SessionAssistantBindingStore(this@AllConversationsActivity).remove(session.sessionId)
                            mainHandler.post {
                                Toast.makeText(this@AllConversationsActivity, R.string.conversation_deleted, Toast.LENGTH_SHORT).show()
                                loadRows()
                            }
                        }
                    }
                    .show()
            }
        })
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadRows()
    }

    private fun loadRows() {
        executor.execute {
            val all = db.messageDao().getAllSessions()
            val safe = all ?: mutableListOf()
            val byCategory: MutableMap<String, MutableList<SessionSummary>> = LinkedHashMap()

            for (s in safe) {
                if (s == null || s.sessionId == null) continue
                val meta = metaStore.get(s.sessionId)
                if (meta.hidden) continue
                if (meta.title != null && meta.title.trim().isNotEmpty()) s.title = meta.title.trim()
                s.outline = if (meta.outline != null) meta.outline.trim() else ""
                s.favorite = meta.favorite
                s.pinned = meta.pinned
                s.hidden = meta.hidden
                s.category = if (meta.category != null && meta.category.trim().isNotEmpty()) meta.category.trim() else "默认"
                val category = s.category
                if (!byCategory.containsKey(category)) byCategory[category] = mutableListOf()
                byCategory[category]!!.add(s)
            }

            val rows = mutableListOf<AllConversationsAdapter.Row>()
            val categories = mutableListOf<String>(*(byCategory.keys.toTypedArray()))
            Collections.sort(categories)
            if (categories.remove("置顶")) categories.add(0, "置顶")
            if (categories.remove("收藏")) categories.add(0, "收藏")

            for (category in categories) {
                val sessions = byCategory[category] ?: continue
                sessions.sortWith(
                    Comparator.comparing<SessionSummary, Boolean> { it.pinned }.reversed()
                        .thenComparing(Comparator.comparing<SessionSummary, Long> { it.lastAt }.reversed())
                )

                val header = AllConversationsAdapter.Row()
                header.header = true
                header.category = category
                header.count = sessions.size
                header.collapsed = collapsedMap.containsKey(category) && collapsedMap[category] == true
                rows.add(header)

                if (header.collapsed) continue
                for (s in sessions) {
                    val item = AllConversationsAdapter.Row()
                    item.header = false
                    item.session = s
                    rows.add(item)
                }
            }
            mainHandler.post { adapter.setRows(rows) }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun applyCategory(session: SessionSummary?, category: String?) {
        if (session == null || session.sessionId == null) return
        var safeCategory = category?.trim() ?: ""
        if (safeCategory.isEmpty()) safeCategory = "默认"
        val meta = metaStore.get(session.sessionId)
        meta.category = safeCategory
        metaStore.save(session.sessionId, meta)
        loadRows()
    }

    private fun showCustomCategoryDialog(session: SessionSummary) {
        val input = EditText(this)
        input.setHint(R.string.category_input_hint)
        input.setSingleLine(true)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_category_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val category = input.text?.toString()?.trim() ?: ""
                if (category.isEmpty()) {
                    Toast.makeText(this, R.string.error_category_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyCategory(session, category)
            }
            .show()
    }
}
