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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import com.example.aichat.chat.ChatCallback

class SessionOutlineActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private var sessionId: String = ""
    private lateinit var outlineStore: SessionOutlineStore
    private lateinit var adapter: SessionOutlineAdapter
    private lateinit var textEmpty: TextView
    private val executor = Executors.newSingleThreadExecutor()

    /** 解析大纲提示词：优先会话级，回退到助手级 */
    private fun resolveOutlinePrompt(): String {
        // 会话级 outlinePrompt
        val sessionPrompt = SessionChatOptionsStore(this).get(sessionId).outlinePrompt.trim()
        if (sessionPrompt.isNotEmpty()) return sessionPrompt
        // 助手级 outlinePrompt
        val assistantId = SessionAssistantBindingStore(this).getAssistantId(sessionId)
        if (assistantId.isNotEmpty()) {
            val assistant = MyAssistantStore(this).getById(assistantId)
            val assistantPrompt = assistant?.options?.outlinePrompt?.trim().orEmpty()
            if (assistantPrompt.isNotEmpty()) return assistantPrompt
        }
        return ""
    }

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

            override fun onSelectedChanged(item: SessionOutlineItem, selected: Boolean) {
                outlineStore.setSelected(sessionId, item.id, selected)
            }
        })
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnAdd.setOnClickListener { showCreateDialog() }
        btnMore?.setOnClickListener { v -> showMoreMenu(v) }
        refreshList()
    }

    private fun showMoreMenu(anchor: View) {
        val labels = listOf("知情注入", "章节计划", "生成卷纲")
        val density = resources.displayMetrics.density
        val popup = android.widget.ListPopupWindow(this)
        popup.setAdapter(android.widget.ArrayAdapter(this, R.layout.item_popup_menu, labels))
        popup.anchorView = anchor
        popup.width = (180 * density + 0.5f).toInt()
        popup.isModal = true
        popup.setBackgroundDrawable(
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_popup_menu)
        )
        // 弹窗显示在按钮上方，与按钮保留 8dp 间距（向上偏移：减去按钮高度 + 弹窗自身高度估算 + 间距）
        // ListPopupWindow 的 verticalOffset 是相对 anchor.top 的偏移；要弹在 anchor 上方，
        // 用负值：- (popupHeight + 8dp)。popupHeight 估算为 itemCount * 48dp。
        val itemHeightDp = 48
        val gapDp = 8
        val estimatedHeightPx = (labels.size * itemHeightDp * density + 0.5f).toInt()
        val gapPx = (gapDp * density + 0.5f).toInt()
        popup.verticalOffset = -(anchor.height + estimatedHeightPx + gapPx)
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            when (position) {
                0 -> runKnowledgeExtraction()
                1 -> runChapterPlanGeneration()
                2 -> runVolumeGeneration()
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
        for (l in inkosListeners) com.example.aichat.inkos.InkosEventBus.removeListener(l)
        inkosListeners.clear()
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

        // Volume：编辑分支独立处理，没有类型选择器，标题/内容都用最简单的方式。
        if (normalizedType == "volume") {
            showEditVolumeDialog(item, view, editTitle, editContent, chipGroupType)
            return
        }

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

    /** 卷大纲编辑：复用同一个 dialog_edit_outline，但隐藏 chipGroupType 与知情章节选择器。 */
    private fun showEditVolumeDialog(
        item: SessionOutlineItem,
        view: View,
        editTitle: EditText,
        editContent: EditText,
        chipGroupType: ChipGroup,
    ) {
        // 隐藏类型选择器及其上方的 label（如果有）
        chipGroupType.visibility = View.GONE
        view.findViewById<View>(R.id.layoutKnowledgeScope)?.visibility = View.GONE
        view.findViewById<TextView>(R.id.dialogOutlineTitle).text = "编辑卷大纲"

        editTitle.hint = "卷标题"
        editTitle.setText(item.title ?: "")
        editContent.setText(item.content ?: "")
        FormInputScrollHelper.enableFor(editContent)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnConfirm).setOnClickListener {
            val title = editTitle.text?.toString()?.trim() ?: ""
            val content = editContent.text?.toString()?.trim() ?: ""
            if (title.isEmpty()) {
                Toast.makeText(this, R.string.error_outline_title_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 保留 type=volume + 已有 volumeChapters；selected 由列表上的开关维护
            item.type = "volume"
            item.title = title
            item.content = content
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

    /**
     * 生成卷纲：选起始/结束章节区间 → 调模型生成纯文本 → 创建一条 type=volume 的大纲条目
     * （默认 selected=true，自动屏蔽该区间内的 chapter 大纲）。
     */
    private fun runVolumeGeneration() {
        val all = outlineStore.getAll(sessionId)
        val chapters = all.filter { "chapter" == outlineStore.normalizeType(it.type) }
        if (chapters.size < 2) {
            Toast.makeText(this, "至少需要 2 个章节才能生成卷纲", Toast.LENGTH_SHORT).show()
            return
        }
        showVolumeRangePicker(chapters) { startIdx, endIdx ->
            val rangeChapters = chapters.subList(startIdx, endIdx + 1)
            val coveredTitles = rangeChapters.map { it.title.trim() }
                .filter { it.isNotEmpty() }
            val coverageLabel = "${rangeChapters.first().title.trim()} ~ ${rangeChapters.last().title.trim()}"
            val volumeTitle = "卷纲：$coverageLabel"

            // 上下文：仅保留区间内的章节 + 全量 人物/世界/知情/资料；交给 OutlinePromptBuilder.buildFull
            val contextItems = mutableListOf<SessionOutlineItem>()
            contextItems.addAll(rangeChapters)
            contextItems.addAll(all.filter { it.type in setOf("task", "world", "knowledge", "material") })
            val promptCtx = OutlinePromptBuilder.appendOutlinePrompt(
                OutlinePromptBuilder.buildFull(contextItems), resolveOutlinePrompt()
            )

            Toast.makeText(this, "正在生成卷纲…", Toast.LENGTH_SHORT).show()
            ChatService(this).generateVolumeOutline(
                volumeTitle, coverageLabel, promptCtx,
                object : ChatCallback {
                    override fun onSuccess(content: String) {
                        runOnUiThread {
                            val item = outlineStore.add(sessionId, "volume", volumeTitle, content)
                            // 写 volumeChapters
                            item.volumeChapters = coveredTitles
                            item.selected = true
                            outlineStore.update(sessionId, item)
                            Toast.makeText(
                                this@SessionOutlineActivity,
                                "已生成卷纲：$volumeTitle",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshList()
                        }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            Toast.makeText(
                                this@SessionOutlineActivity,
                                if (message.isNotBlank()) message else "卷纲生成失败",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
            )
        }
    }

    /** 卷纲区间选择器：两个 Spinner（起始章节 / 结束章节），保证 end >= start。 */
    private fun showVolumeRangePicker(
        chapters: List<SessionOutlineItem>,
        onConfirm: (startIdx: Int, endIdx: Int) -> Unit,
    ) {
        val titles = chapters.mapIndexed { i, c ->
            val t = c.title.trim().ifEmpty { "(无标题)" }
            "${i + 1}. $t"
        }
        val startSpinner = android.widget.Spinner(this)
        val endSpinner = android.widget.Spinner(this)
        val adapterCommon = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, titles)
        adapterCommon.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        startSpinner.adapter = adapterCommon
        endSpinner.adapter = adapterCommon
        // 默认：起始=0，结束=min(9, last)
        startSpinner.setSelection(0)
        endSpinner.setSelection(minOf(9, chapters.size - 1))

        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        container.addView(TextView(this).apply { text = "起始章节" })
        container.addView(startSpinner)
        container.addView(TextView(this).apply { text = "结束章节"; setPadding(0, (12 * dp).toInt(), 0, 0) })
        container.addView(endSpinner)

        MaterialAlertDialogBuilder(this)
            .setTitle("生成卷纲 — 选择区间")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("生成") { _, _ ->
                val s = startSpinner.selectedItemPosition
                val e = endSpinner.selectedItemPosition
                if (s < 0 || e < 0 || e < s) {
                    Toast.makeText(this, "结束章节必须在起始之后", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onConfirm(s, e)
            }
            .show()
    }

    private data class KnowledgeCandidate(
        val chapter: String,
        val title: String,
        val content: String,
        var picked: Boolean,
    )

    /**
     * 知情注入：先选章节范围（默认全部），过滤大纲后调模型按范围抽知情约束，
     * 用户多选确认后批量加入大纲。
     */
    private fun runKnowledgeExtraction() {
        val all = outlineStore.getAll(sessionId)
        val oprompt = resolveOutlinePrompt()
        val chapters = all.filter { "chapter" == outlineStore.normalizeType(it.type) }
        if (chapters.isEmpty()) {
            // 没章节也允许跑：基于人物/世界/已有知情提取通用约束
            val outlineText = OutlinePromptBuilder.appendOutlinePrompt(
                OutlinePromptBuilder.buildFull(all), oprompt
            )
            if (outlineText.isEmpty()) {
                Toast.makeText(this, "大纲为空，无法分析", Toast.LENGTH_SHORT).show()
                return
            }
            showKnowledgeInjectDialog(outlineText, scopeLabel = "通用（无章节）")
            return
        }
        showKnowledgeRangePicker(chapters) { startIdx, endIdx ->
            val rangeChapters = chapters.subList(startIdx, endIdx + 1)
            val rangeTitles = rangeChapters.map { it.title.trim() }
                .filter { it.isNotEmpty() }
            // 过滤大纲：保留范围内章节 + 全部人物 / 世界 / 已有知情 / 资料；卷纲不参与提取。
            val filtered = mutableListOf<SessionOutlineItem>()
            filtered.addAll(rangeChapters)
            for (item in all) {
                when (outlineStore.normalizeType(item.type)) {
                    "task", "world", "knowledge", "material" -> filtered.add(item)
                }
            }
            val baseText = OutlinePromptBuilder.appendOutlinePrompt(
                OutlinePromptBuilder.buildFull(filtered), oprompt
            )
            val scopeLabel = "${rangeChapters.first().title.trim()} ~ ${rangeChapters.last().title.trim()}"
            // 在 prompt 顶部插一个【目标章节范围】小节，让模型知道 chapter 字段只能取自这些标题。
            val outlineText = buildString {
                append("【目标章节范围】\n")
                for (t in rangeTitles) append("- ").append(t).append("\n")
                append("\n").append(baseText)
            }
            showKnowledgeInjectDialog(outlineText, scopeLabel = scopeLabel)
        }
    }

    /** 章节范围选择器（用于知情注入）。复用与卷纲生成相同的 Spinner 模式。 */
    private fun showKnowledgeRangePicker(
        chapters: List<SessionOutlineItem>,
        onConfirm: (startIdx: Int, endIdx: Int) -> Unit,
    ) {
        val titles = chapters.mapIndexed { i, c ->
            val t = c.title.trim().ifEmpty { "(无标题)" }
            "${i + 1}. $t"
        }
        val startSpinner = android.widget.Spinner(this)
        val endSpinner = android.widget.Spinner(this)
        val adapterCommon = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, titles)
        adapterCommon.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        startSpinner.adapter = adapterCommon
        endSpinner.adapter = adapterCommon
        startSpinner.setSelection(0)
        endSpinner.setSelection(chapters.size - 1)

        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        container.addView(TextView(this).apply { text = "起始章节" })
        container.addView(startSpinner)
        container.addView(TextView(this).apply { text = "结束章节"; setPadding(0, (12 * dp).toInt(), 0, 0) })
        container.addView(endSpinner)

        MaterialAlertDialogBuilder(this)
            .setTitle("知情注入 — 选择章节范围")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ ->
                val s = startSpinner.selectedItemPosition
                val e = endSpinner.selectedItemPosition
                if (s < 0 || e < 0 || e < s) {
                    Toast.makeText(this, "结束章节必须在起始之后", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onConfirm(s, e)
            }
            .show()
    }

    private fun showKnowledgeInjectDialog(outlineText: String, scopeLabel: String = "全部") {
        val view = layoutInflater.inflate(R.layout.dialog_knowledge_inject_preview, null)
        val statusView = view.findViewById<TextView>(R.id.textKnowledgeInjectStatus)
        statusView.text = "正在分析（范围：$scopeLabel）…"
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerKnowledgeInject)
        val checkAll = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkKnowledgeSelectAll)
        val summaryView = view.findViewById<TextView>(R.id.textKnowledgeSummary)

        val candidates = mutableListOf<KnowledgeCandidate>()
        val adapter = KnowledgeInjectAdapter(candidates) { updateInjectSummary(summaryView, candidates) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        checkAll.setOnCheckedChangeListener { _, checked ->
            for (c in candidates) c.picked = checked
            adapter.notifyDataSetChanged()
            updateInjectSummary(summaryView, candidates)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("知情注入预览")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("加入大纲") { _, _ ->
                val picked = candidates.filter { it.picked }
                if (picked.isEmpty()) {
                    Toast.makeText(this, "未选择任何条目", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                for (c in picked) {
                    val title = if (c.chapter.isNotEmpty() && c.chapter != "通用")
                        "[${c.chapter}] ${c.title}"
                    else c.title
                    outlineStore.add(sessionId, "knowledge", title, c.content)
                }
                Toast.makeText(this, "已添加 ${picked.size} 条知情约束", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .create()
        dialog.show()
        // 默认禁用确认按钮直到结果回来
        val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        positive?.isEnabled = false

        ChatService(this).extractKnowledgeConstraints(outlineText, object : ChatCallback {
            override fun onSuccess(content: String) {
                runOnUiThread {
                    val parsed = parseKnowledgeCandidates(content)
                    if (parsed.isEmpty()) {
                        statusView.text = "未解析到有效知情约束"
                        return@runOnUiThread
                    }
                    candidates.clear()
                    candidates.addAll(parsed)
                    adapter.notifyDataSetChanged()
                    statusView.text = "共生成 ${parsed.size} 条候选；已默认全选，可取消不需要的"
                    checkAll.isChecked = true
                    updateInjectSummary(summaryView, candidates)
                    positive?.isEnabled = true
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    statusView.text = if (message.isNotBlank()) message else "提取失败"
                }
            }
        })
    }

    private fun updateInjectSummary(view: TextView, list: List<KnowledgeCandidate>) {
        val picked = list.count { it.picked }
        view.text = "已选 $picked / ${list.size}"
    }

    private fun parseKnowledgeCandidates(json: String): List<KnowledgeCandidate> {
        val raw = json.trim()
        if (raw.isEmpty()) return emptyList()
        return try {
            val cleaned = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val node = JsonParser().parse(cleaned)
            // 兼容两种返回：{"items":[...]} （新 prompt 强制）或裸 [...]（旧/兜底）。
            val arr = when {
                node.isJsonArray -> node.asJsonArray
                node.isJsonObject -> {
                    val items = node.asJsonObject.get("items")
                    if (items != null && items.isJsonArray) items.asJsonArray else return emptyList()
                }
                else -> return emptyList()
            }
            val out = mutableListOf<KnowledgeCandidate>()
            for (el in arr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val chapter = obj.get("chapter")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                val content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                if (title.isEmpty() && content.isEmpty()) continue
                out.add(KnowledgeCandidate(chapter, title, content, picked = true))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 多选预览的简单 RecyclerView 适配器：每行 [复选框] [章节标签] 标题 + 内容。 */
    private class KnowledgeInjectAdapter(
        private val items: MutableList<KnowledgeCandidate>,
        private val onToggle: () -> Unit,
    ) : RecyclerView.Adapter<KnowledgeInjectAdapter.VH>() {
        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val check: com.google.android.material.checkbox.MaterialCheckBox = itemView.findViewById(R.id.itemKnowledgeCheck)
            val chapterTag: TextView = itemView.findViewById(R.id.itemKnowledgeChapter)
            val title: TextView = itemView.findViewById(R.id.itemKnowledgeTitle)
            val content: TextView = itemView.findViewById(R.id.itemKnowledgeContent)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_knowledge_inject, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = item.picked
            val chap = item.chapter.ifEmpty { "通用" }
            holder.chapterTag.text = chap
            holder.title.text = item.title.ifEmpty { "(无标题)" }
            holder.content.text = item.content
            holder.check.setOnCheckedChangeListener { _, checked ->
                item.picked = checked
                onToggle()
            }
            holder.itemView.setOnClickListener { holder.check.isChecked = !holder.check.isChecked }
        }
    }

    /**
     * 章节计划：先选目标章节（已有 / 续写新章），再调模型生成 → 弹编辑对话框 → 保存到大纲。
     *
     * Ink toggle 打开时,改走 inkos:把当前完整大纲作为 blurb,POST /api/v1/books/create,
     * 让 inkos pipeline 用大纲生成 story bible / outline 等结构化资料,而不是走本地章节计划。
     */
    private fun runChapterPlanGeneration() {
        if (SessionChatOptionsStore(this).get(sessionId).inkosEnabled) {
            sendOutlineToInkos()
            return
        }
        showChapterTargetPicker { spec ->
            startChapterPlanRequest(spec)
        }
    }

    private fun sendOutlineToInkos() {
        val items = outlineStore.getAll(sessionId)
        if (items.isEmpty()) {
            Toast.makeText(this, "大纲为空,先添加大纲条目再发给 Ink", Toast.LENGTH_SHORT).show()
            return
        }
        val opts = SessionChatOptionsStore(this).get(sessionId)
        val meta = SessionMetaStore(this).get(sessionId)
        val title = meta.title.trim().ifEmpty { opts.sessionTitle.trim() }
            .ifEmpty { "未命名作品-$sessionId" }

        // 子类预设决定 genre 和 book_rules YAML。
        // 用户在会话设置里改过 inkosBookRulesYaml 就走它,没改走预设默认模板。
        val preset = com.example.aichat.inkos.InkosSubtypePresets.byId(opts.inkosSubtype)
        val bookRulesYaml = opts.inkosBookRulesYaml.trim().ifEmpty { preset.defaultBookRulesYaml }
        val blurb = com.example.aichat.inkos.InkosBlurbBuilder.build(items, title, bookRulesYaml)

        Toast.makeText(this, "正在发起 Ink 建书 ($title, ${preset.displayName}, blurb ${blurb.length}字)…", Toast.LENGTH_LONG).show()
        android.util.Log.i("InkBookCreate", "POST start: title=$title genre=${preset.genreId} blurbLen=${blurb.length}")

        executor.execute {
            // 兜底 try/catch — 任何异常都要在 UI 上反映出来, 不能让 executor 静默吞掉。
            val result: com.example.aichat.inkos.InkosClient.BookCreateResult = try {
                com.example.aichat.inkos.InkosClient.createBook(
                    title = title,
                    blurb = blurb,
                    genre = preset.genreId,
                    targetChapters = opts.inkosTargetChapters,
                    chapterWordCount = opts.inkosChapterWordCount,
                )
            } catch (t: Throwable) {
                android.util.Log.e("InkBookCreate", "createBook threw", t)
                com.example.aichat.inkos.InkosClient.BookCreateResult(
                    false, null, "异常: ${t.javaClass.simpleName} ${t.message}"
                )
            }
            android.util.Log.i("InkBookCreate", "POST done: ok=${result.ok} bookId=${result.bookId} err=${result.errorMessage}")

            runOnUiThread {
                if (result.ok && result.bookId != null) {
                    val saved = SessionChatOptionsStore(this).get(sessionId)
                    saved.inkosBookId = result.bookId
                    SessionChatOptionsStore(this).save(sessionId, saved)

                    Toast.makeText(this, "Ink 已开始建书: ${result.bookId}, 跳转到书籍信息看实时进度", Toast.LENGTH_SHORT).show()
                    watchInkosBookProgress(result.bookId, title)

                    // 立刻在 inkos 端建一个 session 绑书 — 否则 studio UI 的 session 导航
                    // 看不到这本书 (POST /books/create 不会自动建 session)。
                    val targetBookId = result.bookId
                    executor.execute {
                        val sid = com.example.aichat.inkos.InkosClient.createBookSession(targetBookId)
                        android.util.Log.i("InkBookCreate", "bound studio session=$sid for bookId=$targetBookId")
                    }

                    // 自动跳到 BookInfoActivity 看事件流, 否则用户找不到进度
                    startActivity(
                        android.content.Intent(this, BookInfoActivity::class.java)
                            .putExtra(BookInfoActivity.EXTRA_SESSION_ID, sessionId)
                    )
                } else {
                    Toast.makeText(
                        this,
                        "Ink 建书失败: ${result.errorMessage ?: "未知错误"} (URL=${com.example.aichat.inkos.InkosClient.BASE_URL})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * SSE 监听给定 bookId 的 `book:created` / `book:error`。匹配到就 Toast + 摘掉自己。
     * Activity 销毁时一并摘掉,避免对死 context 调 Toast。
     */
    private val inkosListeners = mutableSetOf<com.example.aichat.inkos.InkosEventBus.Listener>()
    private fun watchInkosBookProgress(bookId: String, title: String) {
        val listener = object : com.example.aichat.inkos.InkosEventBus.Listener {
            override fun onEvent(event: String, data: com.google.gson.JsonObject) {
                val id = data.get("bookId")?.takeIf { !it.isJsonNull }?.asString
                if (id != bookId) return
                when (event) {
                    "book:created" -> {
                        Toast.makeText(
                            this@SessionOutlineActivity,
                            "Ink 已建好《$title》",
                            Toast.LENGTH_LONG
                        ).show()
                        detach(this)
                    }
                    "book:error" -> {
                        val err = data.get("error")?.takeIf { !it.isJsonNull }?.asString ?: "未知"
                        Toast.makeText(
                            this@SessionOutlineActivity,
                            "Ink 建书失败: $err",
                            Toast.LENGTH_LONG
                        ).show()
                        detach(this)
                    }
                }
            }
        }
        inkosListeners.add(listener)
        com.example.aichat.inkos.InkosEventBus.addListener(listener)
    }

    private fun detach(l: com.example.aichat.inkos.InkosEventBus.Listener) {
        inkosListeners.remove(l)
        com.example.aichat.inkos.InkosEventBus.removeListener(l)
    }

    private data class ChapterPlanTargetSpec(
        val targetTitle: String,
        val isExisting: Boolean,
        val existingItem: SessionOutlineItem?,
        val userHint: String,
        val targetLength: String,
    )

    /**
     * 章节选择对话框：续写新章（CheckBox） / 覆盖已有章节（select 行） 互斥。
     * 默认续写勾选；用户从覆盖 spinner 选择后续写自动取消；续写取消时覆盖自动选最新一章。
     */
    private fun showChapterTargetPicker(onConfirm: (ChapterPlanTargetSpec) -> Unit) {
        val all = outlineStore.getAll(sessionId)
        val chapters = all.filter { "chapter" == outlineStore.normalizeType(it.type) }
        val nextIdx = outlineStore.nextChapterIndex(sessionId)
        val newChapterTitle = getString(R.string.outline_chapter_default_title, nextIdx)

        val view = layoutInflater.inflate(R.layout.dialog_chapter_plan_target, null)
        val rowContinue = view.findViewById<View>(R.id.rowContinueNew)
        val checkContinue = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkContinueNew)
        val labelContinue = view.findViewById<TextView>(R.id.textContinueNewLabel)
        val rowOverwrite = view.findViewById<View>(R.id.rowOverwrite)
        val textOverwrite = view.findViewById<TextView>(R.id.textOverwriteSelected)
        val editHint = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTargetHint)
        val editLength = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTargetLength)
        editLength.setText(loadLastTargetLength())

        labelContinue.text = "新建续写章节：$newChapterTitle"

        // 状态：overwriteIdx == -1 表示当前是续写模式；>=0 表示覆盖第 N 章（chapters 索引）。
        val overwriteIdx = intArrayOf(-1)
        val overwritePlaceholderColor =
            ContextCompat.getColor(this, R.color.ios_section_label)
        val overwriteSelectedColor =
            com.google.android.material.color.MaterialColors.getColor(textOverwrite, com.google.android.material.R.attr.colorOnSurface)

        fun applyState() {
            val isContinue = overwriteIdx[0] < 0
            checkContinue.isChecked = isContinue
            if (isContinue) {
                textOverwrite.text = "未选择"
                textOverwrite.setTextColor(overwritePlaceholderColor)
            } else {
                val item = chapters.getOrNull(overwriteIdx[0])
                val title = item?.title?.trim().orEmpty().ifEmpty { "(无标题)" }
                textOverwrite.text = title
                textOverwrite.setTextColor(overwriteSelectedColor)
            }
        }
        applyState()

        rowContinue.setOnClickListener {
            if (overwriteIdx[0] >= 0) {
                // 当前是覆盖模式 → 切回续写
                overwriteIdx[0] = -1
            } else if (chapters.isNotEmpty()) {
                // 当前是续写 → 取消续写 → 自动选中最新一章（最后一条）
                overwriteIdx[0] = chapters.lastIndex
            }
            // 没有章节时点续写无意义，保持续写
            applyState()
        }

        rowOverwrite.setOnClickListener {
            if (chapters.isEmpty()) {
                Toast.makeText(this, "暂无可覆盖的章节", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val labels = Array(chapters.size) { i ->
                val c = chapters[i]
                val title = c.title.trim().ifEmpty { "(无标题)" }
                val preview = c.content.trim().take(16)
                if (preview.isEmpty()) title else "$title（${preview}…）"
            }
            val current = overwriteIdx[0].coerceAtLeast(0).coerceAtMost(labels.size - 1)
            MaterialAlertDialogBuilder(this)
                .setTitle("选择要覆盖的章节")
                .setSingleChoiceItems(labels, current) { d, which ->
                    overwriteIdx[0] = which
                    applyState()
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnTargetCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnTargetConfirm).setOnClickListener {
            val hint = editHint.text?.toString()?.trim().orEmpty()
            val length = editLength.text?.toString()?.trim().orEmpty()
            val spec = if (overwriteIdx[0] < 0) {
                ChapterPlanTargetSpec(
                    targetTitle = newChapterTitle,
                    isExisting = false,
                    existingItem = null,
                    userHint = hint,
                    targetLength = length,
                )
            } else {
                val item = chapters.getOrNull(overwriteIdx[0])
                if (item == null) {
                    Toast.makeText(this, "目标章节无效", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ChapterPlanTargetSpec(
                    targetTitle = item.title.trim(),
                    isExisting = true,
                    existingItem = item,
                    userHint = hint,
                    targetLength = length,
                )
            }
            if (length.isNotEmpty()) saveLastTargetLength(length)
            dialog.dismiss()
            onConfirm(spec)
        }
        dialog.show()
    }

    private fun startChapterPlanRequest(spec: ChapterPlanTargetSpec) {
        val dialogTitle = if (spec.isExisting) "章节计划：${spec.targetTitle}（覆盖）"
                          else "章节计划：${spec.targetTitle}（新建）"
        val initial = ChapterPlanDraft().apply {
            if (spec.targetLength.isNotEmpty()) targetLength = spec.targetLength
        }
        // resolved: true = 用户已手动保存或取消 → 停止一切后续处理
        // backgroundMode: true = 用户点了"后台"按钮 → 对话框关闭但后台继续, 完成后自动添加草稿
        var resolved = false
        var backgroundMode = false
        val controller = ChapterPlanDialog.show(
            this, dialogTitle, initial,
            initialStatus = "正在收集大纲与上下文…",
            object : ChapterPlanDialog.Callback {
                override fun onCancel() {
                    resolved = true
                }
                override fun onBackground() {
                    backgroundMode = true
                    Toast.makeText(
                        this@SessionOutlineActivity,
                        "章节计划在后台继续生成，完成后将自动添加草稿",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                override fun onSave(edited: ChapterPlanDraft) {
                    resolved = true
                    persistChapterPlanToOutline(spec, edited)
                }
            },
        )

        executor.execute {
            val all = outlineStore.getAll(sessionId)
            val chapters = all.filter { "chapter" == outlineStore.normalizeType(it?.type) }
            val characters = all.filter { "task" == outlineStore.normalizeType(it?.type) }
            val worlds = all.filter { "world" == outlineStore.normalizeType(it?.type) }
            val knowledge = all.filter { "knowledge" == outlineStore.normalizeType(it?.type) }
            val materials = all.filter { "material" == outlineStore.normalizeType(it?.type) }
            val dialogue = collectRecentDialogue(20, 4000)
            val ctx = ChapterPlanContext(
                targetTitle = spec.targetTitle,
                isExisting = spec.isExisting,
                existingContent = spec.existingItem?.content?.trim().orEmpty(),
                allChapters = chapters,
                characters = characters,
                worlds = worlds,
                knowledgeConstraints = knowledge,
                materials = materials,
                recentDialogue = dialogue,
                userHint = spec.userHint,
                targetLength = spec.targetLength,
                outlinePrompt = resolveOutlinePrompt(),
            )

            runOnUiThread {
                if (resolved) return@runOnUiThread
                if (!backgroundMode) controller.setStatus("正在请求章节计划模型…")
                ChatService(this).generateChapterPlanJson(ctx, object : ChatCallback {
                    override fun onPartial(delta: String) {
                        runOnUiThread {
                            if (!resolved && !backgroundMode && delta.trim().isNotEmpty()) {
                                controller.setStatus(delta.trim())
                            }
                        }
                    }

                    override fun onSuccess(content: String) {
                        if (resolved) return
                        runOnUiThread {
                            val draft = parseChapterPlanDraft(content)
                            if (draft != null && spec.targetLength.isNotEmpty()
                                && draft.targetLength.trim().isEmpty()) {
                                draft.targetLength = spec.targetLength
                            }

                            if (backgroundMode && draft != null && draft.hasAnyContent()) {
                                // 后台模式 → 自动添加草稿到大纲
                                persistChapterPlanDraftToOutline(spec, draft)
                            } else if (!backgroundMode && draft != null) {
                                // 对话框仍然打开 → 填入字段让用户编辑
                                controller.applyDraft(draft, fillOnlyEmpty = false)
                                controller.setStatus(
                                    if (draft.hasAnyContent()) "章节计划已生成，可编辑后保存"
                                    else "已解析到结构，但字段为空；可手动填写后保存"
                                )
                            }
                        }
                    }

                    override fun onError(message: String) {
                        if (resolved) return
                        runOnUiThread {
                            if (backgroundMode) {
                                Toast.makeText(
                                    this@SessionOutlineActivity,
                                    "章节计划后台生成失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val msg = if (message.trim().isNotEmpty()) message.trim() else "章节计划生成失败"
                                controller.setStatus("$msg。可手动填写后保存。")
                            }
                        }
                    }
                })
            }
        }
    }

    /** 后台生成完成后自动以"草稿"形式新增到大纲 */
    private fun persistChapterPlanDraftToOutline(spec: ChapterPlanTargetSpec, draft: ChapterPlanDraft) {
        val text = draft.toOutlineText()
        val draftTitle = "${spec.targetTitle} 草稿"
        outlineStore.add(sessionId, "chapter", draftTitle, text)
        Toast.makeText(this, "草稿已自动添加：$draftTitle", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    /** 用户手动保存 → 始终新增草稿条目（不覆盖已有条目） */
    private fun persistChapterPlanToOutline(spec: ChapterPlanTargetSpec, draft: ChapterPlanDraft) {
        val text = draft.toOutlineText()
        val draftTitle = "${spec.targetTitle} 草稿"
        outlineStore.add(sessionId, "chapter", draftTitle, text)
        Toast.makeText(this, "已加入大纲：$draftTitle", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun parseChapterPlanDraft(json: String?): ChapterPlanDraft? {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            val obj = JsonParser().parse(raw).asJsonObject
            ChapterPlanDraft.fromJson(obj)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadLastTargetLength(): String {
        val prefs = getSharedPreferences("chapter_plan_prefs", MODE_PRIVATE)
        return prefs.getString("last_target_length", "") ?: ""
    }

    private fun saveLastTargetLength(value: String) {
        getSharedPreferences("chapter_plan_prefs", MODE_PRIVATE)
            .edit().putString("last_target_length", value).apply()
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
