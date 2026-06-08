package com.example.aichat.story

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.aichat.R
import com.example.aichat.SessionOutlineItem
import com.example.aichat.SessionOutlineStore

/**
 * 按 type 弹结构化编辑对话框 — 取代旧 dialog_edit_outline 里
 * "title + content 双输入" 的扁平 UI。
 *
 * 公共流程: 渲染字段 → 用户改 → 保存时 collect 各字段 → 拼 metaJson +
 * 写回 SessionOutlineStore。新建 (item==null) 与编辑共用同一对话框。
 *
 * 设计哲学: 每种 type 一个 build 函数, 内部用 [Field] DSL 描述字段。
 * 不引入额外依赖, 全程 Android View + Gson。
 */
object StoryEditDialogs {

    /**
     * 入口: 按 type 弹对应编辑器。
     *  - editingItem == null → 新建
     *  - editingItem != null → 编辑
     * onSaved: 保存成功后回调,通常用来 refreshList。
     */
    fun open(
        context: Context,
        sessionId: String?,
        type: String,
        editingItem: SessionOutlineItem?,
        onSaved: () -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_story_edit, null)
        val titleView = view.findViewById<TextView>(R.id.textStoryEditTitle)
        val container = view.findViewById<LinearLayout>(R.id.storyEditFieldContainer)

        titleView.text = dialogTitle(type, isCreate = editingItem == null)

        val store = SessionOutlineStore(context)
        val rolesIdToName: Map<String, String> = store.getAll(sessionId)
            .filter { it.type == StoryTypes.ROLES }
            .associate { it.id to it.title.trim().ifEmpty { "(未命名)" } }

        // 按 type 构建字段
        val fields: List<Field> = when (type) {
            StoryTypes.ROLES -> buildRoleFields(context, container, editingItem)
            StoryTypes.RELATION -> buildRelationFields(context, container, editingItem, rolesIdToName)
            StoryTypes.STATUS -> buildStatusFields(context, container, editingItem, rolesIdToName)
            StoryTypes.SUBPLOT -> buildSubplotFields(context, container, editingItem, rolesIdToName)
            StoryTypes.EMOTION -> buildEmotionFields(context, container, editingItem, rolesIdToName)
            StoryTypes.FORESHADOW -> buildForeshadowFields(context, container, editingItem)
            StoryTypes.RULES -> buildRulesFields(context, container, editingItem)
            StoryTypes.WORLD, StoryTypes.KNOWLEDGE -> buildPlainFields(context, container, editingItem, includeTitle = type == StoryTypes.KNOWLEDGE)
            StoryTypes.CHAPTER, StoryTypes.VOLUME -> buildPlainFields(context, container, editingItem, includeTitle = true)
            else -> buildPlainFields(context, container, editingItem, includeTitle = true)
        }

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnStoryEditCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnStoryEditConfirm).setOnClickListener {
            val collected = fields.associate { it.key to it.collect() }
            val title = collected["__title"]?.trim().orEmpty()
            val content = collected["__content"]?.trim().orEmpty()
            val metaJson = buildMetaJson(type, collected, editingItem)

            // 必填校验
            if (requiresTitle(type) && title.isEmpty()) {
                Toast.makeText(context, "标题不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (editingItem == null) {
                store.add(sessionId, type, title, content, metaJson)
            } else {
                editingItem.type = type
                editingItem.title = title
                editingItem.content = content
                editingItem.metaJson = metaJson
                store.update(sessionId, editingItem)
            }
            dialog.dismiss()
            onSaved()
        }
        dialog.show()
    }

    private fun dialogTitle(type: String, isCreate: Boolean): String {
        val verb = if (isCreate) "新建" else "编辑"
        val label = when (type) {
            StoryTypes.ROLES -> "角色"
            StoryTypes.RELATION -> "角色关系"
            StoryTypes.STATUS -> "状态卡"
            StoryTypes.SUBPLOT -> "支线"
            StoryTypes.EMOTION -> "感情线"
            StoryTypes.FORESHADOW -> "伏笔"
            StoryTypes.RULES -> "叙事规则"
            StoryTypes.CHAPTER -> "章节"
            StoryTypes.VOLUME -> "卷"
            StoryTypes.WORLD -> "世界观条目"
            StoryTypes.KNOWLEDGE -> "知情约束"
            else -> "条目"
        }
        return "$verb$label"
    }

    private fun requiresTitle(type: String): Boolean = when (type) {
        StoryTypes.WORLD -> false
        StoryTypes.RULES -> false
        StoryTypes.RELATION -> false
        StoryTypes.EMOTION -> false
        else -> true
    }

    // ─────────────── Field 抽象 ───────────────

    private class Field(
        val key: String,
        val collect: () -> String,
    )

    private fun appendLabel(ctx: Context, container: LinearLayout, label: String) {
        val tv = TextView(ctx)
        tv.text = label
        tv.textSize = 12f
        tv.setTextColor(ctx.getColor(R.color.ios_section_label))
        tv.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
        container.addView(tv)
    }

    private fun appendEdit(
        ctx: Context,
        container: LinearLayout,
        hint: String,
        initial: String,
        singleLine: Boolean = true,
        numeric: Boolean = false,
    ): EditText {
        val et = EditText(ctx)
        et.hint = hint
        et.setText(initial)
        if (numeric) et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        else if (!singleLine) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else et.inputType = InputType.TYPE_CLASS_TEXT
        if (!singleLine) {
            et.minLines = 3
            et.maxLines = 8
            et.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        et.setBackgroundResource(R.drawable.bg_input_pill)
        et.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        et.textSize = 14f
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(ctx, 2)
        container.addView(et, lp)
        return et
    }

    private fun appendPicker(
        ctx: Context,
        container: LinearLayout,
        hint: String,
        options: List<Pair<String, String>>, // value, label
        selectedValue: String,
    ): () -> String {
        var current = selectedValue.ifBlank { options.firstOrNull()?.first.orEmpty() }
        val display = TextView(ctx)
        display.text = options.firstOrNull { it.first == current }?.second ?: hint
        display.textSize = 14f
        display.setBackgroundResource(R.drawable.bg_input_pill)
        display.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        display.setTextColor(ctx.getColor(android.R.color.tab_indicator_text))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(ctx, 2)
        container.addView(display, lp)
        display.setOnClickListener {
            val labels = options.map { it.second }.toTypedArray()
            val currentIdx = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
            AlertDialog.Builder(ctx)
                .setSingleChoiceItems(labels, currentIdx) { d, which ->
                    current = options[which].first
                    display.text = options[which].second
                    d.dismiss()
                }
                .show()
        }
        return { current }
    }

    // ─────────────── Per-type field builders ───────────────

    private fun buildPlainFields(
        ctx: Context, container: LinearLayout,
        item: SessionOutlineItem?, includeTitle: Boolean,
    ): List<Field> {
        val out = mutableListOf<Field>()
        if (includeTitle) {
            appendLabel(ctx, container, "标题")
            val et = appendEdit(ctx, container, "标题", item?.title.orEmpty())
            out += Field("__title") { et.text.toString().trim() }
        } else {
            out += Field("__title") { "" }
        }
        appendLabel(ctx, container, "内容")
        val etc = appendEdit(ctx, container, "内容", item?.content.orEmpty(), singleLine = false)
        out += Field("__content") { etc.text.toString().trim() }
        return out
    }

    private fun buildRoleFields(
        ctx: Context, container: LinearLayout, item: SessionOutlineItem?,
    ): List<Field> {
        val meta = StoryMeta.parseRole(item?.metaJson)
        val out = mutableListOf<Field>()

        appendLabel(ctx, container, "名字")
        val etName = appendEdit(ctx, container, "角色名字", item?.title.orEmpty())
        out += Field("__title") { etName.text.toString().trim() }

        appendLabel(ctx, container, "戏份")
        val tierGetter = appendPicker(ctx, container, "选择戏份",
            listOf("major" to "主角", "minor" to "重要配角", "extra" to "次要配角"),
            meta.tier)
        out += Field("tier") { tierGetter() }

        appendLabel(ctx, container, "标签 (逗号分隔, e.g. 冷静、毒舌)")
        val etTags = appendEdit(ctx, container, "可空", meta.tags.joinToString("、"))
        out += Field("tags") { etTags.text.toString().trim() }

        appendLabel(ctx, container, "性格 / 核心标签")
        val etPers = appendEdit(ctx, container, "可空", meta.personality, singleLine = false)
        out += Field("personality") { etPers.text.toString().trim() }

        appendLabel(ctx, container, "外观 / 反差细节")
        val etApp = appendEdit(ctx, container, "可空", meta.appearance, singleLine = false)
        out += Field("appearance") { etApp.text.toString().trim() }

        appendLabel(ctx, container, "小传")
        val etBg = appendEdit(ctx, container, "可空", meta.background, singleLine = false)
        out += Field("background") { etBg.text.toString().trim() }

        appendLabel(ctx, container, "内在驱动")
        val etMot = appendEdit(ctx, container, "可空", meta.motivation, singleLine = false)
        out += Field("motivation") { etMot.text.toString().trim() }

        appendLabel(ctx, container, "成长弧光")
        val etArc = appendEdit(ctx, container, "可空", meta.arc, singleLine = false)
        out += Field("arc") { etArc.text.toString().trim() }

        // content 用作 legacy fallback, 这里不开 UI; 内部保留
        out += Field("__content") { item?.content.orEmpty() }
        return out
    }

    private fun buildRelationFields(
        ctx: Context, container: LinearLayout, item: SessionOutlineItem?,
        rolesIdToName: Map<String, String>,
    ): List<Field> {
        val meta = StoryMeta.parseRelation(item?.metaJson)
        val out = mutableListOf<Field>()
        val roleOptions = listOf("" to "(未指定)") + rolesIdToName.toList().map { it.first to it.second }

        appendLabel(ctx, container, "关系名 (可空, 留空则用 from→to 自动)")
        val etTitle = appendEdit(ctx, container, "如 父子 / 师徒", item?.title.orEmpty())
        out += Field("__title") { etTitle.text.toString().trim() }

        appendLabel(ctx, container, "From")
        val from = appendPicker(ctx, container, "选择起点角色", roleOptions, meta.fromRoleId)
        out += Field("fromRoleId") { from() }

        appendLabel(ctx, container, "To")
        val to = appendPicker(ctx, container, "选择终点角色", roleOptions, meta.toRoleId)
        out += Field("toRoleId") { to() }

        appendLabel(ctx, container, "类型 (亲缘 / 敌对 / 暧昧 / 师徒 …)")
        val etKind = appendEdit(ctx, container, "自由文本", meta.kind)
        out += Field("kind") { etKind.text.toString().trim() }

        appendLabel(ctx, container, "强度 (-100 .. 100)")
        val etLevel = appendEdit(ctx, container, "数字", meta.level.toString(), numeric = true)
        out += Field("level") { etLevel.text.toString().trim() }

        appendLabel(ctx, container, "备注")
        val etNote = appendEdit(ctx, container, "可空", meta.note, singleLine = false)
        out += Field("note") { etNote.text.toString().trim() }

        out += Field("__content") { etNote.text.toString().trim() }
        return out
    }

    private fun buildStatusFields(
        ctx: Context, container: LinearLayout, item: SessionOutlineItem?,
        rolesIdToName: Map<String, String>,
    ): List<Field> {
        val meta = StoryMeta.parseStatus(item?.metaJson)
        val out = mutableListOf<Field>()
        val roleOptions = listOf("" to "(全局)") + rolesIdToName.toList().map { it.first to it.second }

        appendLabel(ctx, container, "状态名")
        val etTitle = appendEdit(ctx, container, "如 健康 / 心情 / 武力", item?.title.orEmpty())
        out += Field("__title") { etTitle.text.toString().trim() }

        appendLabel(ctx, container, "归属角色")
        val owner = appendPicker(ctx, container, "选择角色", roleOptions, meta.ownerRoleId)
        out += Field("ownerRoleId") { owner() }

        appendLabel(ctx, container, "当前值")
        val etCur = appendEdit(ctx, container, "如 80 / 焦虑 / 重伤", meta.current, singleLine = false)
        out += Field("current") { etCur.text.toString().trim() }

        val noteTv = TextView(ctx)
        noteTv.text = "历史记录走 wi-server (S6 实装), 本对话框只编辑 current。"
        noteTv.textSize = 11f
        noteTv.setTextColor(ctx.getColor(R.color.ios_section_label))
        noteTv.setPadding(0, dp(ctx, 8), 0, 0)
        container.addView(noteTv)

        out += Field("__content") { etCur.text.toString().trim() }
        return out
    }

    private fun buildSubplotFields(
        ctx: Context, container: LinearLayout, item: SessionOutlineItem?,
        rolesIdToName: Map<String, String>,
    ): List<Field> {
        val meta = StoryMeta.parseSubplot(item?.metaJson)
        val out = mutableListOf<Field>()

        appendLabel(ctx, container, "支线名字")
        val etTitle = appendEdit(ctx, container, "如 凌云彻调查", item?.title.orEmpty())
        out += Field("__title") { etTitle.text.toString().trim() }

        appendLabel(ctx, container, "进度 (0-100)")
        val etProg = appendEdit(ctx, container, "数字", meta.progress.toString(), numeric = true)
        out += Field("progress") { etProg.text.toString().trim() }

        appendLabel(ctx, container, "涉及角色 ID (用 | 分隔, 后续 S5b 改成多选)")
        val etRoles = appendEdit(ctx, container, "可空", meta.linkedRoleIds.joinToString("|"))
        out += Field("linkedRoleIds") { etRoles.text.toString().trim() }

        appendLabel(ctx, container, "说明")
        val etContent = appendEdit(ctx, container, "可空", item?.content.orEmpty(), singleLine = false)
        out += Field("__content") { etContent.text.toString().trim() }

        // milestones: 当前 UI 不开图形编辑, 保留 metaJson 原值
        out += Field("__keep_milestones") { "" }
        return out
    }

    private fun buildEmotionFields(
        ctx: Context, container: LinearLayout, item: SessionOutlineItem?,
        rolesIdToName: Map<String, String>,
    ): List<Field> {
        val meta = StoryMeta.parseEmotion(item?.metaJson)
        val out = mutableListOf<Field>()
        val roleOptions = listOf("" to "(未指定)") + rolesIdToName.toList().map { it.first to it.second }

        appendLabel(ctx, container, "角色 A")
        val a = appendPicker(ctx, container, "选择", roleOptions, meta.roleIdA)
        out += Field("roleIdA") { a() }
        appendLabel(ctx, container, "角色 B")
        val b = appendPicker(ctx, container, "选择", roleOptions, meta.roleIdB)
        out += Field("roleIdB") { b() }

        appendLabel(ctx, container, "阶段")
        val stage = appendPicker(ctx, container, "选择阶段",
            listOf("陌生", "试探", "靠近", "亲密", "破裂", "和解", "分别").map { it to it },
            meta.stage)
        out += Field("stage") { stage() }

        appendLabel(ctx, container, "进度 (0-100)")
        val etProg = appendEdit(ctx, container, "数字", meta.progress.toString(), numeric = true)
        out += Field("progress") { etProg.text.toString().trim() }

        appendLabel(ctx, container, "备注")
        val etNote = appendEdit(ctx, container, "可空", item?.content.orEmpty(), singleLine = false)
        out += Field("__content") { etNote.text.toString().trim() }

        out += Field("__title") { item?.title.orEmpty() }
        return out
    }

    private fun buildForeshadowFields(
        ctx: Context, container: LinearLayout, item: SessionOutlineItem?,
    ): List<Field> {
        val meta = StoryMeta.parseForeshadow(item?.metaJson)
        val out = mutableListOf<Field>()

        appendLabel(ctx, container, "伏笔标题")
        val etTitle = appendEdit(ctx, container, "标题", item?.title.orEmpty())
        out += Field("__title") { etTitle.text.toString().trim() }

        appendLabel(ctx, container, "状态")
        val state = appendPicker(ctx, container, "选择状态",
            listOf("planted" to "已埋下", "developing" to "铺垫中", "paid_off" to "已回收"),
            meta.state)
        out += Field("state") { state() }

        appendLabel(ctx, container, "埋下章节")
        val etPlanted = appendEdit(ctx, container, "可空, 如 第3章", meta.plantedChapter)
        out += Field("plantedChapter") { etPlanted.text.toString().trim() }

        appendLabel(ctx, container, "回收章节 (仅 paid_off 时填)")
        val etPaidOff = appendEdit(ctx, container, "可空", meta.paidOffChapter)
        out += Field("paidOffChapter") { etPaidOff.text.toString().trim() }

        appendLabel(ctx, container, "节奏")
        val halfLife = appendPicker(ctx, container, "选择节奏",
            listOf("short" to "短 (3-5 章)", "medium" to "中 (10 章)", "long" to "长 (跨卷)", "endgame" to "终局"),
            meta.halfLife)
        out += Field("halfLife") { halfLife() }

        appendLabel(ctx, container, "描述")
        val etContent = appendEdit(ctx, container, "可空", item?.content.orEmpty(), singleLine = false)
        out += Field("__content") { etContent.text.toString().trim() }
        return out
    }

    private fun buildRulesFields(
        ctx: Context, container: LinearLayout, item: SessionOutlineItem?,
    ): List<Field> {
        val meta = StoryMeta.parseRules(item?.metaJson)
        val out = mutableListOf<Field>()

        // 标题保持固定字符串, 用户不需要起名
        out += Field("__title") { "叙事规则" }

        appendLabel(ctx, container, "主角设定 (一句话)")
        val etProto = appendEdit(ctx, container, "如 如懿, 乾隆继后", meta.protagonist)
        out += Field("protagonist") { etProto.text.toString().trim() }

        appendLabel(ctx, container, "基调")
        val etTone = appendEdit(ctx, container, "如 古典含蓄, 节奏舒缓", meta.tone)
        out += Field("tone") { etTone.text.toString().trim() }

        appendLabel(ctx, container, "视角")
        val pov = appendPicker(ctx, container, "选择视角",
            listOf("" to "(未设)", "first" to "第一人称", "third-limited" to "第三人称受限", "third-omniscient" to "第三人称全知"),
            meta.pov)
        out += Field("pov") { pov() }

        appendLabel(ctx, container, "禁忌项 (用 | 或换行分隔)")
        val etTaboos = appendEdit(ctx, container, "可空", meta.taboos.joinToString("\n"), singleLine = false)
        out += Field("taboos") { etTaboos.text.toString() }

        appendLabel(ctx, container, "风格参考 (用 | 或换行分隔)")
        val etStyle = appendEdit(ctx, container, "可空, 如 张爱玲 / 严歌苓", meta.styleRefs.joinToString("\n"), singleLine = false)
        out += Field("styleRefs") { etStyle.text.toString() }

        out += Field("__content") { item?.content.orEmpty() }
        return out
    }

    // ─────────────── Build metaJson ───────────────

    private fun buildMetaJson(
        type: String,
        collected: Map<String, String>,
        editingItem: SessionOutlineItem?,
    ): String = when (type) {
        StoryTypes.ROLES -> {
            val m = StoryMeta.RoleMeta(
                tier = collected["tier"].orEmpty().ifBlank { "minor" },
                tags = splitMultiSep(collected["tags"]),
                appearance = collected["appearance"].orEmpty().trim(),
                personality = collected["personality"].orEmpty().trim(),
                background = collected["background"].orEmpty().trim(),
                motivation = collected["motivation"].orEmpty().trim(),
                arc = collected["arc"].orEmpty().trim(),
            )
            StoryMeta.toJson(m)
        }
        StoryTypes.RELATION -> {
            val m = StoryMeta.RelationMeta(
                fromRoleId = collected["fromRoleId"].orEmpty(),
                toRoleId = collected["toRoleId"].orEmpty(),
                kind = collected["kind"].orEmpty().trim(),
                level = collected["level"]?.toIntOrNull()?.coerceIn(-100, 100) ?: 0,
                note = collected["note"].orEmpty().trim(),
            )
            StoryMeta.toJson(m)
        }
        StoryTypes.STATUS -> {
            val m = StoryMeta.StatusMeta(
                ownerRoleId = collected["ownerRoleId"].orEmpty(),
                current = collected["current"].orEmpty().trim(),
            )
            StoryMeta.toJson(m)
        }
        StoryTypes.SUBPLOT -> {
            // milestones 不在 UI 编辑, 保留 editingItem 里的原值
            val keep = editingItem?.let { StoryMeta.parseSubplot(it.metaJson) }
            val m = StoryMeta.SubplotMeta(
                progress = collected["progress"]?.toIntOrNull()?.coerceIn(0, 100) ?: 0,
                milestones = keep?.milestones ?: emptyList(),
                linkedRoleIds = splitPipe(collected["linkedRoleIds"]),
            )
            StoryMeta.toJson(m)
        }
        StoryTypes.EMOTION -> {
            val m = StoryMeta.EmotionMeta(
                roleIdA = collected["roleIdA"].orEmpty(),
                roleIdB = collected["roleIdB"].orEmpty(),
                stage = collected["stage"].orEmpty().ifBlank { "陌生" },
                progress = collected["progress"]?.toIntOrNull()?.coerceIn(0, 100) ?: 0,
            )
            StoryMeta.toJson(m)
        }
        StoryTypes.FORESHADOW -> {
            val m = StoryMeta.ForeshadowMeta(
                state = collected["state"].orEmpty().ifBlank { "planted" },
                plantedChapter = collected["plantedChapter"].orEmpty().trim(),
                paidOffChapter = collected["paidOffChapter"].orEmpty().trim(),
                halfLife = collected["halfLife"].orEmpty().ifBlank { "medium" },
            )
            StoryMeta.toJson(m)
        }
        StoryTypes.RULES -> {
            val m = StoryMeta.RulesMeta(
                protagonist = collected["protagonist"].orEmpty().trim(),
                tone = collected["tone"].orEmpty().trim(),
                pov = collected["pov"].orEmpty(),
                taboos = splitMultiSep(collected["taboos"]),
                styleRefs = splitMultiSep(collected["styleRefs"]),
            )
            StoryMeta.toJson(m)
        }
        else -> editingItem?.metaJson.orEmpty()  // chapter/volume/world/knowledge: 无 metaJson
    }

    private fun splitMultiSep(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split('|', '、', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun splitPipe(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
