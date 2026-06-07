package com.example.aichat

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aichat.story.StoryMeta
import com.example.aichat.story.StoryTypes
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Section-aware outline adapter:
 *  - Section header (折叠/展开)
 *  - Per-item row (folded, 仅显示标题与摘要)
 *  - Per-item expanded view (展示 metaJson 字段 + 编辑/删除按钮)
 *  - Section footer (圆角收尾, 仅展开时显示)
 *
 * 数据组织:
 *  - setItems(allItems): 输入全部 outline 条目,按 type 分组成 SECTIONS_ORDER
 *  - 折叠状态持久化: SectionCollapseStore(SP) 按 sessionId 存哪些 section 折叠了
 *  - 展开单条: 内存中 expandedRowIds set, 不持久化
 */
class SessionOutlineAdapter(
    private val context: Context,
    private val sessionId: String?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface OnItemActionListener {
        fun onEdit(item: SessionOutlineItem)
        fun onDelete(item: SessionOutlineItem)
        fun onSelectedChanged(item: SessionOutlineItem, selected: Boolean) {}
    }

    private val collapseStore = SectionCollapseStore(context)
    private val collapsedSections: MutableSet<String> = collapseStore.get(sessionId).toMutableSet()
    private val expandedRowIds: MutableSet<String> = mutableSetOf()

    private val grouped = LinkedHashMap<String, MutableList<SessionOutlineItem>>()
    private val rows: MutableList<Row> = mutableListOf()

    private var listener: OnItemActionListener? = null

    fun setOnItemActionListener(listener: OnItemActionListener) {
        this.listener = listener
    }

    fun setItems(items: List<SessionOutlineItem>?) {
        grouped.clear()
        for (type in SECTION_ORDER) grouped[type] = mutableListOf()
        if (items != null) {
            for (it in items) {
                if (!StoryTypes.isValid(it.type)) continue
                grouped.getOrPut(it.type) { mutableListOf() }.add(it)
            }
        }
        rebuildRows()
        notifyDataSetChanged()
    }

    private fun rebuildRows() {
        rows.clear()
        for (type in SECTION_ORDER) {
            val list = grouped[type] ?: continue
            if (list.isEmpty() && !ALWAYS_SHOW_EMPTY_SECTIONS.contains(type)) continue
            val collapsed = type in collapsedSections
            rows.add(Row.Header(type, list.size, collapsed))
            if (!collapsed) {
                for ((idx, item) in list.withIndex()) {
                    val isLast = idx == list.size - 1
                    rows.add(Row.Item(item, isLast))
                    if (item.id in expandedRowIds) {
                        rows.add(Row.Expanded(item, isLast))
                    }
                }
                rows.add(Row.Footer(type))
            }
        }
    }

    // ─────────────── viewType / count ───────────────

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> VT_HEADER
        is Row.Item -> VT_ITEM
        is Row.Expanded -> VT_EXPANDED
        is Row.Footer -> VT_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VT_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_outline_section_card_start, parent, false))
            VT_ITEM -> ItemHolder(inflater.inflate(R.layout.item_outline_row, parent, false))
            VT_EXPANDED -> ExpandedHolder(inflater.inflate(R.layout.item_outline_row_expanded, parent, false))
            VT_FOOTER -> FooterHolder(inflater.inflate(R.layout.item_outline_section_card_end, parent, false))
            else -> error("unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Item -> (holder as ItemHolder).bind(row)
            is Row.Expanded -> (holder as ExpandedHolder).bind(row)
            is Row.Footer -> { /* nothing */ }
        }
    }

    // ─────────────── ViewHolders ───────────────

    inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.textSectionTitle)
        private val count: TextView = view.findViewById(R.id.textSectionCount)
        private val chevron: ImageView = view.findViewById(R.id.imageSectionChevron)

        fun bind(row: Row.Header) {
            title.text = SECTION_LABELS[row.type] ?: row.type
            count.text = if (row.itemCount > 0) row.itemCount.toString() else ""
            // 折叠箭头: 折叠时朝下 (0°? 我们的图标默认朝右), 展开时朝上(180°)。
            // 用 rotation 表示: 折叠=90° (chevron 向下), 展开=270° (chevron 向上)。
            chevron.rotation = if (row.collapsed) 90f else 270f
            // 展开时只圆角顶部, 折叠时整体圆角
            itemView.setBackgroundResource(
                if (row.collapsed) R.drawable.bg_outline_section_solo
                else R.drawable.bg_outline_section_top
            )
            itemView.setOnClickListener { toggleSection(row.type) }
        }
    }

    inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.imageRowIcon)
        private val title: TextView = view.findViewById(R.id.textRowTitle)
        private val subtitle: TextView = view.findViewById(R.id.textRowSubtitle)
        private val badge: TextView = view.findViewById(R.id.textRowBadge)
        private val meta: TextView = view.findViewById(R.id.textRowMeta)
        private val chevron: ImageView = view.findViewById(R.id.imageRowChevron)
        private val topSep: View = view.findViewById(R.id.topSeparator)

        fun bind(row: Row.Item) {
            val item = row.item
            // 整体背景: 最后一条用 _bottom (没有 Footer 时圆角收底);
            // 但我们 Footer 作为独立 view 收底, 所以这里始终用 middle, Footer 负责圆角。
            itemView.setBackgroundResource(R.drawable.bg_outline_section_middle)

            // separator: 该 section 内第一条不画顶分隔, 其它都画
            val sectionTypeRow = findSectionHeaderFor(adapterPosition)
            val firstItemPos = sectionTypeRow + 1
            topSep.visibility = if (adapterPosition == firstItemPos) View.GONE else View.VISIBLE

            applyTypeContent(item, icon, title, subtitle, badge, meta)

            chevron.rotation = if (item.id in expandedRowIds) 270f else 0f
            val clickTarget = (itemView as LinearLayout).getChildAt(1) // 第二个子view: row 内容
            clickTarget.setOnClickListener {
                if (item.id in expandedRowIds) expandedRowIds.remove(item.id)
                else expandedRowIds.add(item.id)
                rebuildRows()
                notifyDataSetChanged()
            }
        }
    }

    inner class ExpandedHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val fieldContainer: LinearLayout = view.findViewById(R.id.expandedFieldContainer)
        private val btnEdit = view.findViewById<View>(R.id.btnExpandedEdit)
        private val btnDelete = view.findViewById<View>(R.id.btnExpandedDelete)
        private val switchSelected: MaterialSwitch = view.findViewById(R.id.switchExpandedSelected)

        fun bind(row: Row.Expanded) {
            val item = row.item
            // 末尾条 + 无 footer 时圆底; 但我们一律给 footer, 这里始终 middle
            itemView.setBackgroundResource(R.drawable.bg_outline_section_middle)

            fieldContainer.removeAllViews()
            renderExpandedFields(item, fieldContainer)

            btnEdit.setOnClickListener { listener?.onEdit(item) }
            btnDelete.setOnClickListener { listener?.onDelete(item) }

            // selected 开关 (仅 volume 有意义)
            if (item.type == StoryTypes.VOLUME) {
                switchSelected.visibility = View.VISIBLE
                switchSelected.setOnCheckedChangeListener(null)
                switchSelected.isChecked = item.selected
                switchSelected.text = "启用"
                switchSelected.setOnCheckedChangeListener { _, checked ->
                    item.selected = checked
                    listener?.onSelectedChanged(item, checked)
                }
            } else {
                switchSelected.setOnCheckedChangeListener(null)
                switchSelected.visibility = View.GONE
            }
        }
    }

    inner class FooterHolder(view: View) : RecyclerView.ViewHolder(view)

    // ─────────────── Row content per type ───────────────

    private fun applyTypeContent(
        item: SessionOutlineItem,
        icon: ImageView,
        title: TextView,
        subtitle: TextView,
        badge: TextView,
        meta: TextView,
    ) {
        title.text = item.title.trim().ifEmpty { defaultTitleFor(item.type) }
        subtitle.visibility = View.GONE
        badge.visibility = View.GONE
        meta.visibility = View.GONE
        icon.visibility = View.VISIBLE

        when (item.type) {
            StoryTypes.CHAPTER -> {
                icon.setImageResource(R.drawable.ic_chapter_dot)
                val chars = item.content.length
                if (chars > 0) {
                    meta.text = chars.toString()
                    meta.visibility = View.VISIBLE
                }
            }
            StoryTypes.VOLUME -> {
                icon.setImageResource(R.drawable.ic_action_outline)
                val cov = item.volumeChapters.filter { it.isNotBlank() }
                if (cov.isNotEmpty()) {
                    subtitle.text = "覆盖 ${cov.size} 章"
                    subtitle.visibility = View.VISIBLE
                }
            }
            StoryTypes.ROLES -> {
                icon.setImageResource(R.drawable.ic_role)
                val m = StoryMeta.parseRole(item.metaJson)
                badge.text = tierLabel(m.tier)
                badge.visibility = View.VISIBLE
                if (m.tags.isNotEmpty()) {
                    subtitle.text = m.tags.joinToString("、")
                    subtitle.visibility = View.VISIBLE
                }
            }
            StoryTypes.RELATION -> {
                icon.setImageResource(R.drawable.ic_action_category)
                val m = StoryMeta.parseRelation(item.metaJson)
                val from = roleName(m.fromRoleId)
                val to = roleName(m.toRoleId)
                title.text = "$from → $to"
                if (m.kind.isNotBlank()) {
                    badge.text = m.kind
                    badge.visibility = View.VISIBLE
                }
                if (m.level != 0) {
                    meta.text = m.level.toString()
                    meta.visibility = View.VISIBLE
                }
            }
            StoryTypes.SUBPLOT -> {
                icon.setImageResource(R.drawable.ic_action_outline)
                val m = StoryMeta.parseSubplot(item.metaJson)
                meta.text = "${m.progress.coerceIn(0, 100)}%"
                meta.visibility = View.VISIBLE
            }
            StoryTypes.EMOTION -> {
                icon.setImageResource(R.drawable.ic_action_favorite)
                val m = StoryMeta.parseEmotion(item.metaJson)
                val a = roleName(m.roleIdA)
                val b = roleName(m.roleIdB)
                title.text = "$a ↔ $b"
                if (m.stage.isNotBlank()) {
                    badge.text = m.stage
                    badge.visibility = View.VISIBLE
                }
            }
            StoryTypes.FORESHADOW -> {
                icon.setImageResource(R.drawable.ic_action_pin)
                val m = StoryMeta.parseForeshadow(item.metaJson)
                badge.text = foreshadowStateLabel(m.state)
                badge.visibility = View.VISIBLE
            }
            StoryTypes.STATUS -> {
                icon.setImageResource(R.drawable.ic_action_read)
                val m = StoryMeta.parseStatus(item.metaJson)
                val owner = if (m.ownerRoleId.isNotBlank()) roleName(m.ownerRoleId) else null
                if (owner != null) {
                    subtitle.text = owner
                    subtitle.visibility = View.VISIBLE
                }
            }
            StoryTypes.RULES -> {
                icon.setImageResource(R.drawable.ic_action_settings)
            }
            StoryTypes.WORLD -> {
                icon.setImageResource(R.drawable.ic_action_outline)
            }
            StoryTypes.KNOWLEDGE -> {
                icon.setImageResource(R.drawable.ic_action_hide)
            }
            else -> {
                icon.visibility = View.GONE
            }
        }
    }

    private fun defaultTitleFor(type: String): String = when (type) {
        StoryTypes.RULES -> "叙事规则"
        StoryTypes.WORLD -> "世界观条目"
        StoryTypes.KNOWLEDGE -> "知情约束"
        StoryTypes.STATUS -> "状态卡"
        StoryTypes.RELATION -> "角色关系"
        StoryTypes.SUBPLOT -> "支线"
        StoryTypes.EMOTION -> "感情线"
        StoryTypes.FORESHADOW -> "伏笔"
        else -> "(无标题)"
    }

    private fun renderExpandedFields(item: SessionOutlineItem, container: LinearLayout) {
        val ctx = container.context
        when (item.type) {
            StoryTypes.ROLES -> {
                val m = StoryMeta.parseRole(item.metaJson)
                appendField(ctx, container, "性格", m.personality)
                appendField(ctx, container, "外观/反差", m.appearance)
                appendField(ctx, container, "小传", m.background)
                appendField(ctx, container, "内在驱动", m.motivation)
                appendField(ctx, container, "成长弧光", m.arc)
                // legacy content fallback
                if (item.content.isNotBlank()
                    && m.personality.isBlank() && m.background.isBlank()) {
                    appendField(ctx, container, "描述", item.content.trim())
                }
            }
            StoryTypes.RELATION -> {
                val m = StoryMeta.parseRelation(item.metaJson)
                appendField(ctx, container, "类型", m.kind)
                if (m.level != 0) appendField(ctx, container, "强度", m.level.toString())
                appendField(ctx, container, "备注", m.note.ifBlank { item.content.trim() })
            }
            StoryTypes.SUBPLOT -> {
                val m = StoryMeta.parseSubplot(item.metaJson)
                appendField(ctx, container, "进度", "${m.progress.coerceIn(0, 100)}%")
                if (m.linkedRoleIds.isNotEmpty()) {
                    appendField(ctx, container, "涉及角色", m.linkedRoleIds.joinToString("、") { roleName(it) })
                }
                appendField(ctx, container, "说明", item.content.trim())
                if (m.milestones.isNotEmpty()) {
                    appendLabel(ctx, container, "里程碑")
                    for (ms in m.milestones) {
                        val mark = if (ms.done) "✓" else "·"
                        val ch = if (ms.chapter.isNotBlank()) "[${ms.chapter}] " else ""
                        appendPlain(ctx, container, "$mark $ch${ms.desc}")
                    }
                }
            }
            StoryTypes.EMOTION -> {
                val m = StoryMeta.parseEmotion(item.metaJson)
                appendField(ctx, container, "阶段", m.stage)
                appendField(ctx, container, "进度", "${m.progress.coerceIn(0, 100)}%")
                appendField(ctx, container, "备注", item.content.trim())
            }
            StoryTypes.STATUS -> {
                val m = StoryMeta.parseStatus(item.metaJson)
                appendField(ctx, container, "归属", if (m.ownerRoleId.isBlank()) "全局" else roleName(m.ownerRoleId))
                appendField(ctx, container, "当前状态", m.current.ifBlank { item.content.trim() })
                appendLabel(ctx, container, "历史 (服务端记录)")
                appendPlain(ctx, container, "查看完整历史需要 wi-server (S6 实装)")
            }
            StoryTypes.FORESHADOW -> {
                val m = StoryMeta.parseForeshadow(item.metaJson)
                appendField(ctx, container, "状态", foreshadowStateLabel(m.state))
                appendField(ctx, container, "埋下", m.plantedChapter)
                appendField(ctx, container, "回收", m.paidOffChapter)
                appendField(ctx, container, "节奏", halfLifeLabel(m.halfLife))
                appendField(ctx, container, "描述", item.content.trim())
            }
            StoryTypes.RULES -> {
                val m = StoryMeta.parseRules(item.metaJson)
                appendField(ctx, container, "主角设定", m.protagonist)
                appendField(ctx, container, "基调", m.tone)
                appendField(ctx, container, "视角", m.pov)
                appendField(ctx, container, "时态", m.tense)
                if (m.taboos.isNotEmpty()) appendField(ctx, container, "禁忌", m.taboos.joinToString("、"))
                if (m.styleRefs.isNotEmpty()) appendField(ctx, container, "风格参考", m.styleRefs.joinToString("、"))
                if (m.customYaml.isNotBlank()) {
                    appendLabel(ctx, container, "自定义 YAML (老数据迁移保底)")
                    appendPlain(ctx, container, m.customYaml.take(300))
                }
            }
            StoryTypes.CHAPTER, StoryTypes.VOLUME, StoryTypes.WORLD, StoryTypes.KNOWLEDGE -> {
                appendField(ctx, container, "内容", item.content.trim())
                if (item.type == StoryTypes.VOLUME && item.volumeChapters.isNotEmpty()) {
                    appendField(ctx, container, "覆盖章节", item.volumeChapters.filter { it.isNotBlank() }.joinToString("、"))
                }
            }
            else -> appendField(ctx, container, "内容", item.content.trim())
        }
    }

    private fun appendField(ctx: Context, container: LinearLayout, label: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        val tv = TextView(ctx)
        tv.text = "$label: $v"
        tv.textSize = 13f
        tv.setPadding(0, 4, 0, 0)
        tv.setTextColor(ctx.getColor(R.color.ios_section_label))
        container.addView(tv)
    }

    private fun appendLabel(ctx: Context, container: LinearLayout, label: String) {
        val tv = TextView(ctx)
        tv.text = label
        tv.textSize = 12f
        tv.setPadding(0, 8, 0, 2)
        tv.setTextColor(ctx.getColor(R.color.ios_section_label))
        container.addView(tv)
    }

    private fun appendPlain(ctx: Context, container: LinearLayout, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        val tv = TextView(ctx)
        tv.text = v
        tv.textSize = 13f
        tv.setPadding(0, 2, 0, 0)
        container.addView(tv)
    }

    // ─────────────── Helpers ───────────────

    private fun toggleSection(type: String) {
        if (type in collapsedSections) collapsedSections.remove(type)
        else collapsedSections.add(type)
        collapseStore.save(sessionId, collapsedSections)
        rebuildRows()
        notifyDataSetChanged()
    }

    private fun findSectionHeaderFor(pos: Int): Int {
        for (i in pos downTo 0) {
            if (rows[i] is Row.Header) return i
        }
        return 0
    }

    private fun roleName(roleId: String): String {
        if (roleId.isBlank()) return "(未指定)"
        val roles = grouped[StoryTypes.ROLES] ?: return "(已删除)"
        val match = roles.firstOrNull { it.id == roleId } ?: return "(已删除)"
        return match.title.trim().ifEmpty { "(未命名)" }
    }

    private fun tierLabel(tier: String): String = when (tier) {
        "major" -> "主角"
        "minor" -> "配角"
        "extra" -> "次配"
        else -> tier.ifBlank { "配角" }
    }

    private fun foreshadowStateLabel(state: String): String = when (state) {
        "planted" -> "已埋下"
        "developing" -> "铺垫中"
        "paid_off" -> "已回收"
        else -> "未分类"
    }

    private fun halfLifeLabel(h: String): String = when (h) {
        "short" -> "短"
        "medium" -> "中"
        "long" -> "长"
        "endgame" -> "终局"
        else -> h
    }

    // ─────────────── Inner types ───────────────

    sealed class Row {
        data class Header(val type: String, val itemCount: Int, val collapsed: Boolean) : Row()
        data class Item(val item: SessionOutlineItem, val isLastInSection: Boolean) : Row()
        data class Expanded(val item: SessionOutlineItem, val isLastInSection: Boolean) : Row()
        data class Footer(val type: String) : Row()
    }

    companion object {
        const val VT_HEADER = 0
        const val VT_ITEM = 1
        const val VT_EXPANDED = 2
        const val VT_FOOTER = 3

        /** 渲染顺序对齐 OutlinePromptBuilder 的注入顺序 + 视觉重要性。 */
        val SECTION_ORDER: List<String> = listOf(
            StoryTypes.CHAPTER,
            StoryTypes.VOLUME,
            StoryTypes.ROLES,
            StoryTypes.RELATION,
            StoryTypes.STATUS,
            StoryTypes.SUBPLOT,
            StoryTypes.EMOTION,
            StoryTypes.FORESHADOW,
            StoryTypes.WORLD,
            StoryTypes.KNOWLEDGE,
            StoryTypes.RULES,
        )

        val SECTION_LABELS: Map<String, String> = mapOf(
            StoryTypes.CHAPTER to "章节",
            StoryTypes.VOLUME to "卷",
            StoryTypes.ROLES to "角色",
            StoryTypes.RELATION to "角色关系",
            StoryTypes.STATUS to "状态卡",
            StoryTypes.SUBPLOT to "支线",
            StoryTypes.EMOTION to "感情线",
            StoryTypes.FORESHADOW to "伏笔池",
            StoryTypes.WORLD to "世界观",
            StoryTypes.KNOWLEDGE to "知情约束",
            StoryTypes.RULES to "叙事规则",
        )

        /** 即使没有数据也保留 section header (引导用户去新建)。 */
        val ALWAYS_SHOW_EMPTY_SECTIONS: Set<String> = setOf(
            StoryTypes.CHAPTER, StoryTypes.ROLES, StoryTypes.RULES
        )
    }
}
