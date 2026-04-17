package com.example.aichat

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.gson.JsonParser
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.LinkedHashSet
import java.util.UUID
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
        val btnMore = findViewById<MaterialButton?>(R.id.btnMore)

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
        btnMore?.setOnClickListener { v -> showMoreMenu(v) }
        refreshList()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "泄密审计")
        popup.menu.add(0, 2, 1, "知情注入")
        popup.menu.add(0, 3, 2, "章节计划")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { runLeakageAudit(); true }
                2 -> { runKnowledgeExtraction(); true }
                3 -> { runChapterPlanGeneration(); true }
                else -> false
            }
        }
        popup.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_session_outline, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_new_novel) {
            copyOutlineToNewSession()
            true
        } else super.onOptionsItemSelected(item)
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
        val chipGroupType = view.findViewById<ChipGroup>(R.id.chipGroupOutlineType)
        val typeValues = arrayOf("chapter", "task", "world", "knowledge", "material")
        val selected = intArrayOf(0)
        val prevSelected = intArrayOf(-1)
        val savedChapterTitle = arrayOf(getString(R.string.outline_chapter_default_title,
            outlineStore.nextChapterIndex(sessionId)))

        FormInputScrollHelper.enableFor(editContent)
        applyTitleMode(view, 0, prevType = -1, isCreate = true,
            chapterTitle = savedChapterTitle[0], knowledgePreTitle = null)

        bindTypeChipSelection(view, selected, null) { newTypeIndex ->
            val prev = prevSelected[0]
            if (prev == 0) savedChapterTitle[0] = editTitle.text?.toString()?.trim() ?: ""
            prevSelected[0] = newTypeIndex
            applyTitleMode(view, newTypeIndex, prevType = prev, isCreate = true,
                chapterTitle = savedChapterTitle[0], knowledgePreTitle = null)
        }
        chipGroupType.check(R.id.chipTypeChapter)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnConfirm).setOnClickListener {
            val content = editContent.text?.toString()?.trim() ?: ""
            val selectedType = typeValues[selected[0]]
            when (selected[0]) {
                3 -> { // 知情约束
                    val scopeTitle = collectKnowledgeScope(view)
                    if (scopeTitle.isEmpty()) {
                        Toast.makeText(this, "请至少选择一个章节", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    outlineStore.add(sessionId, selectedType, scopeTitle, content)
                }
                2, 4 -> { // 世界背景、资料（无标题）
                    outlineStore.add(sessionId, selectedType, "", content)
                }
                else -> { // 章节、人物资料（标题必填）
                    val title = editTitle.text?.toString()?.trim() ?: ""
                    if (title.isEmpty()) {
                        Toast.makeText(this, R.string.error_outline_title_empty, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    outlineStore.add(sessionId, selectedType, title, content)
                }
            }
            refreshList()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showEditDialog(item: SessionOutlineItem?) {
        if (item == null) return
        val view = layoutInflater.inflate(R.layout.dialog_edit_outline, null)
        val editTitle = view.findViewById<EditText>(R.id.editOutlineTitle)
        val editContent = view.findViewById<EditText>(R.id.editOutlineContent)
        val chipGroupType = view.findViewById<ChipGroup>(R.id.chipGroupOutlineType)
        val typeValues = arrayOf("chapter", "task", "world", "knowledge", "material")
        val normalizedType = outlineStore.normalizeType(item.type)
        val defaultType = indexOfType(typeValues, normalizedType)
        val selected = intArrayOf(defaultType)
        val prevSelected = intArrayOf(-1)
        val savedChapterTitle = arrayOf(if (normalizedType == "chapter") item.title ?: "" else "")

        editTitle.setText(item.title ?: "")
        editContent.setText(item.content ?: "")
        FormInputScrollHelper.enableFor(editContent)

        view.findViewById<TextView>(R.id.dialogOutlineTitle).text = getString(R.string.outline_edit_title)
        applyTitleMode(view, defaultType, prevType = -1, isCreate = false,
            chapterTitle = savedChapterTitle[0], knowledgePreTitle = item.title)

        bindTypeChipSelection(view, selected, null) { newTypeIndex ->
            val prev = prevSelected[0]
            if (prev == 0) savedChapterTitle[0] = editTitle.text?.toString()?.trim() ?: ""
            prevSelected[0] = newTypeIndex
            applyTitleMode(view, newTypeIndex, prevType = prev, isCreate = false,
                chapterTitle = savedChapterTitle[0], knowledgePreTitle = null)
        }
        chipGroupType.check(typeIndexToChipId(defaultType))

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnConfirm).setOnClickListener {
            val content = editContent.text?.toString()?.trim() ?: ""
            val selectedType = typeValues[selected[0]]
            when (selected[0]) {
                3 -> { // 知情约束
                    val scopeTitle = collectKnowledgeScope(view)
                    if (scopeTitle.isEmpty()) {
                        Toast.makeText(this, "请至少选择一个章节", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    item.type = selectedType; item.title = scopeTitle; item.content = content
                }
                2, 4 -> { // 世界背景、资料（无标题）
                    item.type = selectedType; item.title = ""; item.content = content
                }
                else -> { // 章节、人物资料
                    val title = editTitle.text?.toString()?.trim() ?: ""
                    if (title.isEmpty()) {
                        Toast.makeText(this, R.string.error_outline_title_empty, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    item.type = selectedType; item.title = title; item.content = content
                }
            }
            outlineStore.update(sessionId, item)
            refreshList()
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * 根据当前选中的类型索引，统一控制标题输入框的显示/隐藏/提示文字。
     *
     * typeIndex: 0=章节  1=人物资料  2=世界背景  3=知情约束  4=资料
     * prevType:  切换前的类型（-1 表示初始加载）
     */
    private fun applyTitleMode(
        view: View,
        typeIndex: Int,
        prevType: Int,
        isCreate: Boolean,
        chapterTitle: String,
        knowledgePreTitle: String?
    ) {
        val editTitle = view.findViewById<EditText>(R.id.editOutlineTitle)
        val layoutScope = view.findViewById<LinearLayout>(R.id.layoutKnowledgeScope)
        val titleView = view.findViewById<TextView>(R.id.dialogOutlineTitle)
        val baseDialogTitle = if (isCreate) getString(R.string.outline_add_title)
                              else getString(R.string.outline_edit_title)

        // 重置
        layoutScope.visibility = View.GONE
        editTitle.visibility = View.VISIBLE
        titleView.text = baseDialogTitle

        when (typeIndex) {
            0 -> { // 章节：恢复之前保存的标题
                editTitle.hint = "标题，例如：第1章"
                editTitle.setText(chapterTitle)
                editTitle.setSelection(chapterTitle.length)
            }
            1 -> { // 人物资料：提示改为姓名/团体，从章节切过来则清空
                editTitle.hint = "姓名/团体"
                if (prevType == 0) editTitle.setText("")
            }
            2 -> { // 世界背景：隐藏标题
                editTitle.visibility = View.GONE
            }
            3 -> { // 知情约束：隐藏标题，显示 Spinner
                editTitle.visibility = View.GONE
                layoutScope.visibility = View.VISIBLE
                titleView.text = "知情约束"
                setupKnowledgeSelector(view, knowledgePreTitle)
            }
            4 -> { // 资料：隐藏标题
                editTitle.visibility = View.GONE
            }
        }
    }

    // 当前 knowledge 弹窗的章节列表和选中集合（dialog 生命周期内有效）
    private val chapterOptions = mutableListOf<Pair<String, String>>() // (displayText, saveTitle)
    private val knowledgeSelected = mutableSetOf<String>()             // 选中的 saveTitle

    /** 初始化知情章节选择行 */
    private fun setupKnowledgeSelector(view: View, preselectedTitle: String?) {
        // 构建章节选项
        chapterOptions.clear()
        val items = outlineStore.getAll(sessionId)
        val seen = LinkedHashSet<String>()
        for (item in items ?: emptyList()) {
            if ("chapter" != outlineStore.normalizeType(item?.type)) continue
            val title = item?.title?.trim() ?: ""
            if (title.isEmpty() || !seen.add(title)) continue
            val content = item.content?.trim() ?: ""
            val preview = content.take(8)
            val display = if (preview.isEmpty()) title else "$title（$preview）"
            chapterOptions.add(Pair(display, title))
        }

        // 解析预选值
        knowledgeSelected.clear()
        val pre = preselectedTitle?.trim() ?: ""
        when {
            pre.isEmpty() || pre == "全部" -> knowledgeSelected.add("全部")
            else -> pre.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { knowledgeSelected.add(it) }
        }

        updateKnowledgeSummary(view)

        view.findViewById<View>(R.id.btnKnowledgePicker).setOnClickListener {
            showKnowledgePickerDialog(view)
        }
    }

    /** 更新选择摘要文字 */
    private fun updateKnowledgeSummary(view: View) {
        val tv = view.findViewById<TextView>(R.id.textKnowledgeSelection) ?: return
        tv.text = when {
            knowledgeSelected.contains("全部") -> "全部"
            knowledgeSelected.isEmpty() -> "请选择章节"
            else -> knowledgeSelected.joinToString("、")
        }
    }

    /** 弹出 iOS 风格的多选 picker */
    private fun showKnowledgePickerDialog(parentView: View) {
        // 深拷贝当前选中状态，取消时可回退
        val draft = mutableSetOf<String>().apply { addAll(knowledgeSelected) }

        // 构建内容视图
        val ctx = this
        val dialogRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dpToPx(14).toFloat()
            }
            background = bg
        }

        // 标题
        val tvTitle = TextView(ctx).apply {
            text = "选择知情章节"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(14))
        }
        dialogRoot.addView(tvTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 分割线
        fun divider() = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A000000"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        dialogRoot.addView(divider())

        // 可滚动的章节列表
        val scrollView = ScrollView(ctx)
        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // "全部"选项
        val cbAll = CheckBox(ctx).apply {
            text = "全部"
            textSize = 15f
            isChecked = draft.contains("全部")
            setPadding(dpToPx(20), 0, dpToPx(16), 0)
        }
        cbAll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48))
        listLayout.addView(cbAll)
        listLayout.addView(divider())

        // 各章节 checkbox
        val chapterCheckboxes = mutableListOf<CheckBox>()
        for ((display, saveTitle) in chapterOptions) {
            val cb = CheckBox(ctx).apply {
                text = display
                textSize = 14f
                isChecked = draft.contains("全部") || draft.contains(saveTitle)
                tag = saveTitle
                setPadding(dpToPx(20), 0, dpToPx(16), 0)
            }
            cb.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48))
            listLayout.addView(cb)
            chapterCheckboxes.add(cb)
        }

        // "全部"联动逻辑
        cbAll.setOnCheckedChangeListener { _, checked ->
            chapterCheckboxes.forEach { it.isChecked = checked }
        }
        chapterCheckboxes.forEach { cb ->
            cb.setOnCheckedChangeListener { _, _ ->
                val anyUnchecked = chapterCheckboxes.any { !it.isChecked }
                if (anyUnchecked && cbAll.isChecked) {
                    cbAll.setOnCheckedChangeListener(null)
                    cbAll.isChecked = false
                    cbAll.setOnCheckedChangeListener { _, checked ->
                        chapterCheckboxes.forEach { it.isChecked = checked }
                    }
                }
            }
        }

        scrollView.addView(listLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val scrollLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        scrollLp.height = minOf(dpToPx(280), dpToPx(48) * (chapterOptions.size + 1) + dpToPx(4))
        dialogRoot.addView(scrollView, scrollLp)

        dialogRoot.addView(divider())

        // 底部 iOS 按钮行
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50))
        }
        val tvCancel = TextView(ctx).apply {
            text = "取消"; textSize = 16f; gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#8E8E93"))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#20000000")),
                null, null)
        }
        val divV = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A000000"))
            layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val tvConfirm = TextView(ctx).apply {
            text = "确定"; textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            val tv2 = android.util.TypedValue()
            theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv2, true)
            setTextColor(tv2.data)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#20000000")),
                null, null)
        }
        btnRow.addView(tvCancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        btnRow.addView(divV)
        btnRow.addView(tvConfirm, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        dialogRoot.addView(btnRow)

        val picker = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(dialogRoot)
            .create()
        picker.window?.setBackgroundDrawableResource(android.R.color.transparent)

        tvCancel.setOnClickListener { picker.dismiss() }
        tvConfirm.setOnClickListener {
            knowledgeSelected.clear()
            if (cbAll.isChecked) {
                knowledgeSelected.add("全部")
            } else {
                chapterCheckboxes.filter { it.isChecked }.forEach {
                    knowledgeSelected.add(it.tag as String)
                }
            }
            updateKnowledgeSummary(parentView)
            picker.dismiss()
        }
        picker.show()
    }

    /** 收集知情章节选中结果（"全部" 或逗号拼接标题） */
    private fun collectKnowledgeScope(view: View): String {
        if (knowledgeSelected.contains("全部")) return "全部"
        return knowledgeSelected.filter { it.isNotEmpty() }.joinToString(",")
    }

    private fun indexOfType(values: Array<String>, type: String?): Int {
        for (i in values.indices) {
            if (values[i] == type) return i
        }
        return 0
    }

    private fun bindTypeChipSelection(
        view: View?,
        selected: IntArray,
        editTitleForChapterAutofill: EditText?,
        onKnowledgeToggle: ((Int) -> Unit)? = null
    ) {
        if (view == null || selected.isEmpty()) return
        bindTypeChip(view, R.id.chipTypeChapter, 0, selected, editTitleForChapterAutofill, onKnowledgeToggle)
        bindTypeChip(view, R.id.chipTypeTask, 1, selected, editTitleForChapterAutofill, onKnowledgeToggle)
        bindTypeChip(view, R.id.chipTypeWorld, 2, selected, editTitleForChapterAutofill, onKnowledgeToggle)
        bindTypeChip(view, R.id.chipTypeKnowledge, 3, selected, editTitleForChapterAutofill, onKnowledgeToggle)
        bindTypeChip(view, R.id.chipTypeMaterial, 4, selected, editTitleForChapterAutofill, onKnowledgeToggle)
    }

    private fun bindTypeChip(
        view: View,
        chipId: Int,
        typeIndex: Int,
        selected: IntArray,
        editTitleForChapterAutofill: EditText?,
        onKnowledgeToggle: ((Int) -> Unit)?
    ) {
        val chip = view.findViewById<Chip?>(chipId) ?: return
        chip.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) return@setOnCheckedChangeListener
            selected[0] = typeIndex
            onKnowledgeToggle?.invoke(typeIndex)
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
            1 -> R.id.chipTypeTask
            2 -> R.id.chipTypeWorld
            3 -> R.id.chipTypeKnowledge
            4 -> R.id.chipTypeMaterial
            else -> R.id.chipTypeChapter
        }
    }

    // Bug 修复：UP 导航时确保带上正确的 sessionId，
    // 避免 Android 重建父 Activity 时丢失 sessionId 导致大纲串掉
    override fun getSupportParentActivityIntent(): Intent? {
        return Intent(this, ChatSessionActivity::class.java)
            .putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId)
    }

    override fun getParentActivityIntent(): Intent? {
        return Intent(this, ChatSessionActivity::class.java)
            .putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId)
    }

    private fun copyOutlineToNewSession() {
        val items = outlineStore.getAll(sessionId)
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.new_novel_empty_outline, Toast.LENGTH_SHORT).show()
            return
        }

        // 1. 查找当前对话绑定的助手（必须是作家类助手，才能看到大纲入口）
        val bindingStore = SessionAssistantBindingStore(this)
        val assistantId = bindingStore.getAssistantId(sessionId)

        val newSessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val newItems = items.map { src ->
            SessionOutlineItem().also { dst ->
                dst.id = UUID.randomUUID().toString()
                dst.type = src.type
                dst.title = src.title
                dst.content = src.content
                dst.createdAt = now
                dst.updatedAt = now
            }
        }

        // 2. 复制大纲到新对话
        outlineStore.saveAll(newSessionId, newItems)

        // 3. 将同一个作家助手绑定到新对话，新对话才能显示大纲入口
        if (assistantId.isNotEmpty()) {
            bindingStore.bind(newSessionId, assistantId)
        }

        Toast.makeText(this, R.string.new_novel_copied, Toast.LENGTH_SHORT).show()
        val intent = Intent(this, ChatSessionActivity::class.java)
            .putExtra(ChatSessionActivity.EXTRA_SESSION_ID, newSessionId)
        if (assistantId.isNotEmpty()) {
            intent.putExtra(ChatSessionActivity.EXTRA_ASSISTANT_ID, assistantId)
        }
        startActivity(intent)
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

    /** 知情注入：从大纲+最近对话提取知情约束并自动添加到大纲 */
    private fun runKnowledgeExtraction() {
        val all = outlineStore.getAll(sessionId)
        val sb = StringBuilder()
        for (item in all) {
            if (item == null) continue
            val type = outlineStore.normalizeType(item.type)
            if ("knowledge" == type) continue // 不把已有知情约束塞回去
            val typeLabel = when (type) {
                "chapter" -> "章节"
                "task" -> "人物资料"
                "world" -> "世界背景"
                "material" -> "资料"
                else -> type
            }
            val title = item.title?.trim() ?: ""
            val content = item.content?.trim() ?: ""
            if (title.isEmpty() && content.isEmpty()) continue
            sb.append("[").append(typeLabel).append("] ")
            if (title.isNotEmpty()) sb.append(title)
            if (content.isNotEmpty()) {
                if (title.isNotEmpty()) sb.append("：")
                sb.append(content)
            }
            sb.append("\n")
        }
        val outlineText = sb.toString().trim()

        Toast.makeText(this, "正在提取知情约束…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val dialogue = collectRecentDialogue(20, 4000)
            runOnUiThread {
                ChatService(this).extractKnowledgeConstraints(
                    outlineText,
                    dialogue,
                    object : ChatService.ChatCallback {
                        override fun onSuccess(content: String) {
                            runOnUiThread {
                                val n = parseAndAddKnowledgeItems(content)
                                if (n > 0) {
                                    Toast.makeText(
                                        this@SessionOutlineActivity,
                                        "已添加 $n 条知情约束",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshList()
                                } else {
                                    Toast.makeText(
                                        this@SessionOutlineActivity,
                                        "未解析到有效知情约束",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        override fun onError(message: String) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@SessionOutlineActivity,
                                    if (message.isNotEmpty()) message else "提取失败",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    })
            }
        }
    }

    /** 解析模型返回的 JSON 数组并添加到大纲（type=knowledge），返回成功添加的条数 */
    private fun parseAndAddKnowledgeItems(json: String): Int {
        val raw = json.trim()
        if (raw.isEmpty()) return 0
        return try {
            // 容错：去除可能的 ```json ... ``` 包裹
            val cleaned = raw.removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val arr = JsonParser().parse(cleaned)
            if (!arr.isJsonArray) return 0
            var added = 0
            for (el in arr.asJsonArray) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                val content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                if (title.isEmpty() && content.isEmpty()) continue
                outlineStore.add(sessionId, "knowledge", title, content)
                added++
            }
            added
        } catch (e: Exception) {
            0
        }
    }

    /** 章节计划：从最近对话生成下一章计划并以 chapter 类型加入大纲 */
    private fun runChapterPlanGeneration() {
        Toast.makeText(this, "正在生成章节计划…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val dialogue = collectRecentDialogue(20, 4000)
            runOnUiThread {
                ChatService(this).generateChapterPlanJson(
                    "请根据当前故事进展，生成下一章的章节计划。",
                    dialogue,
                    object : ChatService.ChatCallback {
                        override fun onSuccess(content: String) {
                            runOnUiThread {
                                val formatted = formatChapterPlanJson(content)
                                if (formatted.isEmpty()) {
                                    Toast.makeText(
                                        this@SessionOutlineActivity,
                                        "未解析到有效章节计划",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@runOnUiThread
                                }
                                val next = outlineStore.nextChapterIndex(sessionId)
                                val title = "章节$next"
                                outlineStore.add(sessionId, "chapter", title, formatted)
                                Toast.makeText(
                                    this@SessionOutlineActivity,
                                    "已加入大纲：$title",
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshList()
                            }
                        }

                        override fun onError(message: String) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@SessionOutlineActivity,
                                    if (message.isNotEmpty()) message else "章节计划生成失败",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    })
            }
        }
    }

    /** 将章节计划 JSON 对象格式化为大纲可读文本 */
    private fun formatChapterPlanJson(json: String): String {
        val raw = json.trim()
        if (raw.isEmpty()) return ""
        return try {
            val cleaned = raw.removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val obj = JsonParser().parse(cleaned)
            if (!obj.isJsonObject) return raw
            val o = obj.asJsonObject
            val sb = StringBuilder()
            fun strOf(key: String): String =
                o.get(key)?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
            val plotDrive = strOf("plotDrive")
            val chapterGoal = strOf("chapterGoal")
            val misbelief = strOf("misbelief")
            val emotion = strOf("emotion")
            val targetLength = strOf("targetLength")
            if (plotDrive.isNotEmpty()) sb.append("【剧情推进】\n").append(plotDrive).append("\n\n")
            if (chapterGoal.isNotEmpty()) sb.append("【本章目标】\n").append(chapterGoal).append("\n\n")
            if (misbelief.isNotEmpty()) sb.append("【关键误解/冲突】\n").append(misbelief).append("\n\n")
            if (emotion.isNotEmpty()) sb.append("【情感基调】\n").append(emotion).append("\n\n")
            // characterDrives: array of {name, drive}
            val cdArr = o.get("characterDrives")
            if (cdArr != null && cdArr.isJsonArray && cdArr.asJsonArray.size() > 0) {
                sb.append("【人物驱动】\n")
                for (el in cdArr.asJsonArray) {
                    if (!el.isJsonObject) continue
                    val obj2 = el.asJsonObject
                    val name = obj2.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                    val drive = obj2.get("drive")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                    if (name.isEmpty() && drive.isEmpty()) continue
                    sb.append("- ")
                    if (name.isNotEmpty()) sb.append(name)
                    if (drive.isNotEmpty()) {
                        if (name.isNotEmpty()) sb.append("：")
                        sb.append(drive)
                    }
                    sb.append("\n")
                }
                sb.append("\n")
            }
            if (targetLength.isNotEmpty()) sb.append("【目标字数】").append(targetLength)
            sb.toString().trim()
        } catch (e: Exception) {
            raw
        }
    }

    /** 取最近 N 条对话消息拼接，超长时截断 */
    private fun collectRecentDialogue(maxMessages: Int, maxChars: Int): String {
        val sb = StringBuilder()
        try {
            val messages = AppDatabase.getInstance(this).messageDao().getBySession(sessionId)
            val start = if (messages.size > maxMessages) messages.size - maxMessages else 0
            for (i in start until messages.size) {
                val m = messages[i] ?: continue
                val role = if (m.role == Message.ROLE_USER) "用户" else "AI"
                val content = m.content?.trim() ?: continue
                if (content.isEmpty()) continue
                sb.append(role).append("：").append(content).append("\n")
            }
        } catch (ignored: Exception) {}
        val text = sb.toString().trim()
        return if (text.length > maxChars) text.substring(text.length - maxChars) else text
    }
}
