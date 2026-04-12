package com.example.aichat

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.LinkedHashSet
import java.util.concurrent.Executors

class SessionOutlineActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private var sessionId: String = ""
    private lateinit var outlineStore: SessionOutlineStore
    private lateinit var adapter: SessionOutlineAdapter
    private lateinit var textEmpty: TextView
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_outline)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        outlineStore = SessionOutlineStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        textEmpty = findViewById(R.id.textOutlineEmpty)
        val recycler = findViewById<RecyclerView>(R.id.recyclerOutline)
        val btnAdd = findViewById<MaterialButton>(R.id.btnAddOutline)
        val btnLeakAudit = findViewById<MaterialButton?>(R.id.btnLeakAudit)

        adapter = SessionOutlineAdapter()
        adapter.setOnItemActionListener(object : SessionOutlineAdapter.OnItemActionListener {
            override fun onEdit(item: SessionOutlineItem) {
                showEditDialog(item)
            }

            override fun onDelete(item: SessionOutlineItem) {
                if (item.id == null) return
                outlineStore.delete(sessionId, item.id)
                refreshList()
            }
        })
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnAdd.setOnClickListener { showCreateDialog() }
        btnLeakAudit?.setOnClickListener { runLeakageAudit() }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun refreshList() {
        val list = outlineStore.getAll(sessionId)
        adapter.setItems(list)
        textEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showCreateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_edit_outline, null)
        val editTitle = view.findViewById<EditText>(R.id.editOutlineTitle)
        val editContent = view.findViewById<EditText>(R.id.editOutlineContent)
        val typeValues = arrayOf("chapter", "material", "task", "world", "knowledge")
        val chipGroupType = view.findViewById<ChipGroup?>(R.id.chipGroupOutlineType)
        val selected = intArrayOf(0)
        bindTypeChipSelection(view, selected, editTitle)
        chipGroupType?.check(R.id.chipTypeChapter)
        FormInputScrollHelper.enableFor(editContent)

        val next = outlineStore.nextChapterIndex(sessionId)
        editTitle.setText(getString(R.string.outline_chapter_default_title, next))

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_AIChat_MaterialAlertDialog)
            .setTitle(R.string.outline_add_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = editTitle.text?.toString()?.trim() ?: ""
                val content = editContent.text?.toString()?.trim() ?: ""
                val selectedType = typeValues[selected[0]]
                if ("knowledge" == selectedType) {
                    val chapterTitles = getChapterTitles()
                    if (chapterTitles.isEmpty()) {
                        Toast.makeText(this, R.string.error_no_chapters_for_knowledge, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    showChapterTitlePicker(chapterTitles, "") { pickedTitle ->
                        outlineStore.add(sessionId, selectedType, pickedTitle, content)
                        refreshList()
                    }
                    return@setPositiveButton
                }
                if (title.isEmpty()) {
                    Toast.makeText(this, R.string.error_outline_title_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                outlineStore.add(sessionId, selectedType, title, content)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        moveDialogUp(dialog, 40)
    }

    private fun showEditDialog(item: SessionOutlineItem?) {
        if (item == null) return
        val view = layoutInflater.inflate(R.layout.dialog_edit_outline, null)
        val editTitle = view.findViewById<EditText>(R.id.editOutlineTitle)
        val editContent = view.findViewById<EditText>(R.id.editOutlineContent)
        val chipGroupType = view.findViewById<ChipGroup?>(R.id.chipGroupOutlineType)
        val typeValues = arrayOf("chapter", "material", "task", "world", "knowledge")
        val defaultType = indexOfType(typeValues, outlineStore.normalizeType(item.type))
        val selected = intArrayOf(defaultType)
        bindTypeChipSelection(view, selected, null)
        chipGroupType?.check(typeIndexToChipId(defaultType))
        FormInputScrollHelper.enableFor(editContent)

        editTitle.setText(item.title ?: "")
        editContent.setText(item.content ?: "")

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.outline_edit_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedType = typeValues[selected[0]]
                val editedTitle = editTitle.text?.toString()?.trim() ?: ""
                val editedContent = editContent.text?.toString()?.trim() ?: ""
                if ("knowledge" == selectedType) {
                    val chapterTitles = getChapterTitles()
                    if (chapterTitles.isEmpty()) {
                        Toast.makeText(this, R.string.error_no_chapters_for_knowledge, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    showChapterTitlePicker(chapterTitles, item.title) { pickedTitle ->
                        item.type = selectedType
                        item.title = pickedTitle
                        item.content = editedContent
                        outlineStore.update(sessionId, item)
                        refreshList()
                    }
                    return@setPositiveButton
                }
                if (editedTitle.isEmpty()) {
                    Toast.makeText(this, R.string.error_outline_title_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                item.type = selectedType
                item.title = editedTitle
                item.content = editedContent
                outlineStore.update(sessionId, item)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        moveDialogUp(dialog, 40)
    }

    private fun indexOfType(values: Array<String>, type: String?): Int {
        for (i in values.indices) {
            if (values[i] == type) return i
        }
        return 0
    }

    private fun bindTypeChipSelection(view: View?, selected: IntArray, editTitleForChapterAutofill: EditText?) {
        if (view == null || selected.isEmpty()) return
        bindTypeChip(view, R.id.chipTypeChapter, 0, selected, editTitleForChapterAutofill)
        bindTypeChip(view, R.id.chipTypeMaterial, 1, selected, editTitleForChapterAutofill)
        bindTypeChip(view, R.id.chipTypeTask, 2, selected, editTitleForChapterAutofill)
        bindTypeChip(view, R.id.chipTypeWorld, 3, selected, editTitleForChapterAutofill)
        bindTypeChip(view, R.id.chipTypeKnowledge, 4, selected, editTitleForChapterAutofill)
    }

    private fun bindTypeChip(view: View, chipId: Int, typeIndex: Int, selected: IntArray, editTitleForChapterAutofill: EditText?) {
        val chip = view.findViewById<Chip?>(chipId) ?: return
        chip.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) return@setOnCheckedChangeListener
            selected[0] = typeIndex
            if (typeIndex == 0 && editTitleForChapterAutofill != null
                && (editTitleForChapterAutofill.text == null
                        || editTitleForChapterAutofill.text.toString().trim().isEmpty())
            ) {
                val nextChapter = outlineStore.nextChapterIndex(sessionId)
                editTitleForChapterAutofill.setText(getString(R.string.outline_chapter_default_title, nextChapter))
            }
        }
    }

    private fun getChapterTitles(): List<String> {
        val all = mutableListOf<String>()
        val items = outlineStore.getAll(sessionId)
        if (items.isNullOrEmpty()) return all
        val uniq = LinkedHashSet<String>()
        for (one in items) {
            if (one == null) continue
            if ("chapter" != outlineStore.normalizeType(one.type)) continue
            val title = one.title?.trim() ?: ""
            if (title.isEmpty()) continue
            uniq.add(title)
        }
        all.addAll(uniq)
        return all
    }

    private fun showChapterTitlePicker(
        chapterTitles: List<String>,
        defaultTitle: String?,
        callback: (String) -> Unit
    ) {
        if (chapterTitles.isEmpty()) return
        val preferred = defaultTitle?.trim() ?: ""
        var defaultIndex = 0
        if (preferred.isNotEmpty()) {
            val idx = chapterTitles.indexOf(preferred)
            if (idx >= 0) defaultIndex = idx
        }
        val titles = chapterTitles.toTypedArray()
        val checked = intArrayOf(defaultIndex)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_related_chapter_title)
            .setSingleChoiceItems(titles, defaultIndex) { _, which -> checked[0] = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val idx = checked[0]
                if (idx < 0 || idx >= titles.size) {
                    Toast.makeText(this, R.string.error_select_chapter, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                callback(titles[idx])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun moveDialogUp(dialog: androidx.appcompat.app.AlertDialog?, offsetDp: Int) {
        if (dialog == null) return
        val window: Window = dialog.window ?: return
        val params: WindowManager.LayoutParams = window.attributes ?: return
        params.gravity = Gravity.CENTER
        params.y = -dpToPx(offsetDp)
        window.attributes = params
    }

    private fun dpToPx(dp: Int): Int {
        return Math.round(dp * resources.displayMetrics.density)
    }

    private fun typeIndexToChipId(index: Int): Int {
        return when (index) {
            1 -> R.id.chipTypeMaterial
            2 -> R.id.chipTypeTask
            3 -> R.id.chipTypeWorld
            4 -> R.id.chipTypeKnowledge
            else -> R.id.chipTypeChapter
        }
    }

    private fun runLeakageAudit() {
        val all = outlineStore.getAll(sessionId)
        val knowledge = StringBuilder()
        for (item in all) {
            if (item == null || "knowledge" != outlineStore.normalizeType(item.type)) continue
            val title = item.title?.trim() ?: ""
            val content = item.content?.trim() ?: ""
            if (title.isEmpty() && content.isEmpty()) continue
            knowledge.append("- ")
            if (title.isNotEmpty()) knowledge.append(title)
            if (content.isNotEmpty()) {
                if (title.isNotEmpty()) knowledge.append("：")
                knowledge.append(content)
            }
            knowledge.append("\n")
        }
        if (knowledge.toString().trim().isEmpty()) {
            Toast.makeText(this, R.string.error_no_knowledge_outline, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.auditing_ai_response, Toast.LENGTH_SHORT).show()
        executor.execute {
            var latestAssistant = ""
            try {
                val messages = AppDatabase.getInstance(this).messageDao().getBySession(sessionId)
                for (i in messages.indices.reversed()) {
                    val m = messages[i]
                    if (m != null && m.role == Message.ROLE_ASSISTANT) {
                        latestAssistant = m.content?.trim() ?: ""
                        break
                    }
                }
            } catch (ignored: Exception) {}
            val aiText = latestAssistant
            runOnUiThread {
                if (aiText.isEmpty()) {
                    Toast.makeText(this, "当前会话还没有可审计的AI回复", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                ChatService(this).auditNovelLeakage(knowledge.toString().trim(), aiText, object : ChatService.ChatCallback {
                    override fun onSuccess(content: String) {
                        runOnUiThread {
                            MaterialAlertDialogBuilder(this@SessionOutlineActivity)
                                .setTitle(R.string.leak_audit_result_title)
                                .setMessage(content?.trim() ?: "")
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            Toast.makeText(
                                this@SessionOutlineActivity,
                                if (message != null && message.trim().isNotEmpty()) message else "审计失败",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                })
            }
        }
    }
}
