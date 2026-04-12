package com.example.aichat

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
