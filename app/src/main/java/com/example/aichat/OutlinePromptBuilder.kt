package com.example.aichat

import com.example.aichat.story.StoryMeta
import com.example.aichat.story.StoryTypes

/**
 * 把 outline 条目按类型分组、应用 selected 与 volume 覆盖逻辑后，输出结构化 prompt 段落。
 *
 * 任何需要往 LLM prompt 注入大纲的场景都应该走这里，避免再出现"按时间顺序混合标 type"的拼法。
 *
 * 分组顺序（参考 inkos 5-SECTION 设计但本地化）：
 *   1. 叙事规则 rules — 字面给模型, YAML 围栏
 *   2. 卷大纲 volume
 *   3. 章节大纲 chapter (去掉被卷纲覆盖的)
 *   4. 角色档案 roles (按 tier 分组)
 *   5. 角色关系 relation (一句一条)
 *   6. 世界背景 world
 *   7. 知情约束 knowledge (+ 硬约束句)
 *   8. 支线进度 subplot
 *   9. 感情线 emotion
 *  10. 伏笔池 foreshadow (按 state 分子段)
 *  11. 状态卡 status (按 ownerRole 分组)
 *
 * Volume 覆盖逻辑：
 *  - 收集所有 selected=true 的 volume，把它们的 volumeChapters 取并集 → coveredTitles
 *  - 章节遍历时，若 chapter.title 在 coveredTitles 中且该 chapter selected=true，则被跳过
 *  - selected=false 的 volume 自身不输出
 */
object OutlinePromptBuilder {

    /**
     * @param items 全部 outline 条目（任意顺序，内部按 createdAt 已经排好）
     * @param includeKnowledgeEnforcement true 时在知情约束段后追加"角色只能用已知信息"等强约束句
     * @param maxVisibleChapters 最多展示几章大纲（null = 全部）。用于"写第 N 章时不暴露后续章节"。
     *   例：写第 2 章 → maxVisibleChapters=2，只注入第 1、2 章，第 3 章起截断。
     * @return 结构化大纲文本；若 outline 完全为空（去掉 deselected 之后），返回空串
     */
    fun build(
        items: List<SessionOutlineItem>,
        includeKnowledgeEnforcement: Boolean = true,
        maxVisibleChapters: Int? = null,
    ): String {
        if (items.isEmpty()) return ""

        // 按 type 分组
        val rules = items.filter { it.type == StoryTypes.RULES }
        val volumes = items.filter { it.type == StoryTypes.VOLUME }
        val chapters = items.filter { it.type == StoryTypes.CHAPTER }
        val roles = items.filter { it.type == StoryTypes.ROLES }
        val relations = items.filter { it.type == StoryTypes.RELATION }
        val worlds = items.filter { it.type == StoryTypes.WORLD }
        val knowledge = items.filter { it.type == StoryTypes.KNOWLEDGE }
        val subplots = items.filter { it.type == StoryTypes.SUBPLOT }
        val emotions = items.filter { it.type == StoryTypes.EMOTION }
        val foreshadows = items.filter { it.type == StoryTypes.FORESHADOW }
        val statuses = items.filter { it.type == StoryTypes.STATUS }

        // volume 覆盖集
        val activeVolumes = volumes.filter { it.selected }
        val coveredTitles: Set<String> = activeVolumes
            .flatMap { it.volumeChapters }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        // 角色 id → 显示名查找表
        val roleIdToName: Map<String, String> = roles
            .filter { it.id.isNotBlank() }
            .associate { it.id to (it.title.trim().ifEmpty { "(未命名角色)" }) }

        val sb = StringBuilder()

        // 1. 叙事规则
        appendRules(sb, rules)

        // 2. 卷大纲
        if (activeVolumes.isNotEmpty()) {
            sb.append("【卷大纲（已对早期章节做总览）】\n")
            for (v in activeVolumes) {
                val title = v.title.trim().ifEmpty { "(未命名卷)" }
                val content = v.content.trim()
                val coverage = v.volumeChapters.filter { it.isNotBlank() }.joinToString("、")
                sb.append("# ").append(title)
                if (coverage.isNotEmpty()) sb.append("（覆盖：").append(coverage).append("）")
                sb.append("\n")
                if (content.isNotEmpty()) sb.append(content).append("\n")
                sb.append("\n")
            }
        }

        // 3. 章节大纲（去掉被卷纲覆盖的，去掉 deselected，按 maxVisibleChapters 截断）
        val allVisibleChapters = chapters.filter {
            it.selected && (it.title.trim().isEmpty() || it.title.trim() !in coveredTitles)
        }
        val visibleChapters = if (maxVisibleChapters != null && maxVisibleChapters > 0)
            allVisibleChapters.take(maxVisibleChapters) else allVisibleChapters
        val hiddenCount = allVisibleChapters.size - visibleChapters.size
        if (visibleChapters.isNotEmpty()) {
            sb.append("【章节大纲】\n")
            for ((idx, c) in visibleChapters.withIndex()) {
                val title = c.title.trim().ifEmpty { "(无标题)" }
                val content = c.content.trim()
                sb.append(idx + 1).append(". ").append(title)
                if (content.isNotEmpty()) {
                    sb.append("\n")
                    sb.append(indentLines(content, "   "))
                }
                sb.append("\n")
            }
            if (hiddenCount > 0) {
                sb.append("（后续 $hiddenCount 章大纲已隐藏，避免影响当前章节创作）\n")
            }
            sb.append("\n")
        }

        // 4. 角色档案 (按 tier 分组)
        appendRoles(sb, roles)

        // 5. 角色关系
        appendRelations(sb, relations, roleIdToName)

        // 6. 世界
        appendUntitledSection(sb, "世界背景", worlds)

        // 7. 知情约束
        appendTitledSection(sb, "知情约束", knowledge)
        if (includeKnowledgeEnforcement && knowledge.any { it.selected }) {
            sb.append("\n知情边界硬约束：\n")
            sb.append("1) 角色只能基于其已知信息行动、发言与推理；\n")
            sb.append("2) 未知信息不得被角色提及或据此决策；\n")
            sb.append("3) 让角色得知信息须先写出获取路径（目击/对话/文件/推理）；\n")
            sb.append("4) 不要把读者已知当作角色已知。\n\n")
        }

        // 8. 支线
        appendSubplots(sb, subplots, roleIdToName)

        // 9. 感情线
        appendEmotions(sb, emotions, roleIdToName)

        // 10. 伏笔池 (按 state 分子段)
        appendForeshadows(sb, foreshadows)

        // 11. 状态卡 (按 ownerRole 分组)
        appendStatuses(sb, statuses, roleIdToName)

        return sb.toString().trim()
    }

    /**
     * 给"完整大纲（无任何过滤）"建文本，常用于子任务（章节计划/卷纲生成）的 user prompt。
     * 不应用 selected/volume 覆盖；按章节/人物/世界/知情/资料/卷纲分组。
     */
    fun buildFull(items: List<SessionOutlineItem>): String {
        val all = items.map { copyForceSelected(it) }
        return build(all, includeKnowledgeEnforcement = false)
    }

    private fun copyForceSelected(it: SessionOutlineItem): SessionOutlineItem =
        it.copy(selected = true)

    // ───────────────── Section builders ─────────────────

    private fun appendRules(sb: StringBuilder, rules: List<SessionOutlineItem>) {
        val active = rules.filter { it.selected }
        if (active.isEmpty()) return
        // 通常只有一条 rules; 拼第一条即可,多条情况下后面的也拼上
        sb.append("【叙事规则（请严格遵守）】\n")
        sb.append("```yaml\n")
        for ((i, r) in active.withIndex()) {
            val meta = StoryMeta.parseRules(r.metaJson)
            val yaml = StoryMeta.buildRulesYaml(meta)
            if (i > 0) sb.append("---\n")
            sb.append(yaml).append("\n")
        }
        sb.append("```\n\n")
    }

    private fun appendRoles(sb: StringBuilder, roles: List<SessionOutlineItem>) {
        val active = roles.filter { it.selected }
        if (active.isEmpty()) return
        sb.append("【角色档案】\n")
        // tier 分组: major → minor → extra → 未指定
        val grouped = active.groupBy {
            StoryMeta.parseRole(it.metaJson).tier.ifBlank { "minor" }
        }
        val order = listOf("major", "minor", "extra")
        val ordered = (order + (grouped.keys - order.toSet())).distinct()
        for (tier in ordered) {
            val list = grouped[tier] ?: continue
            sb.append("## ").append(tierLabel(tier)).append("\n")
            for (r in list) {
                val name = r.title.trim().ifEmpty { "(未命名角色)" }
                val meta = StoryMeta.parseRole(r.metaJson)
                sb.append("### ").append(name)
                if (meta.tags.isNotEmpty()) sb.append("  [").append(meta.tags.joinToString("、")).append("]")
                sb.append("\n")
                appendRoleField(sb, "性格", meta.personality)
                appendRoleField(sb, "外观/反差", meta.appearance)
                appendRoleField(sb, "小传", meta.background)
                appendRoleField(sb, "内在驱动", meta.motivation)
                appendRoleField(sb, "成长弧光", meta.arc)
                // legacy: content 字段(老 task 迁过来的纯文本)
                val legacy = r.content.trim()
                if (legacy.isNotEmpty()
                    && meta.personality.isBlank() && meta.background.isBlank()) {
                    sb.append("- 描述: ").append(legacy).append("\n")
                }
                sb.append("\n")
            }
        }
    }

    private fun appendRoleField(sb: StringBuilder, label: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        sb.append("- ").append(label).append(": ").append(v).append("\n")
    }

    private fun tierLabel(tier: String): String = when (tier) {
        "major" -> "主角"
        "minor" -> "重要配角"
        "extra" -> "次要配角"
        else -> tier
    }

    private fun appendRelations(
        sb: StringBuilder,
        relations: List<SessionOutlineItem>,
        roleIdToName: Map<String, String>,
    ) {
        val active = relations.filter { it.selected }
        if (active.isEmpty()) return
        sb.append("【角色关系矩阵】\n")
        for (r in active) {
            val meta = StoryMeta.parseRelation(r.metaJson)
            val from = roleNameOrFallback(meta.fromRoleId, roleIdToName)
            val to = roleNameOrFallback(meta.toRoleId, roleIdToName)
            val kind = meta.kind.trim().ifEmpty { r.title.trim() }.ifEmpty { "关系" }
            sb.append("- ").append(from).append(" → ").append(to)
                .append("：").append(kind)
            if (meta.level != 0) sb.append("（强度 ").append(meta.level).append("）")
            val note = meta.note.trim().ifEmpty { r.content.trim() }
            if (note.isNotEmpty()) sb.append(" — ").append(note)
            sb.append("\n")
        }
        sb.append("\n")
    }

    private fun appendSubplots(
        sb: StringBuilder,
        subplots: List<SessionOutlineItem>,
        roleIdToName: Map<String, String>,
    ) {
        val active = subplots.filter { it.selected }
        if (active.isEmpty()) return
        sb.append("【支线进度】\n")
        for (s in active) {
            val meta = StoryMeta.parseSubplot(s.metaJson)
            val title = s.title.trim().ifEmpty { "(未命名支线)" }
            sb.append("- ").append(title)
                .append("（进度 ").append(meta.progress.coerceIn(0, 100)).append("%")
            if (meta.linkedRoleIds.isNotEmpty()) {
                val names = meta.linkedRoleIds.map { roleNameOrFallback(it, roleIdToName) }
                sb.append("，涉及：").append(names.joinToString("、"))
            }
            sb.append("）\n")
            if (s.content.isNotBlank()) {
                sb.append("  说明: ").append(s.content.trim()).append("\n")
            }
            for (m in meta.milestones) {
                val mark = if (m.done) "✓" else "·"
                sb.append("  ").append(mark).append(" ")
                if (m.chapter.isNotBlank()) sb.append("[").append(m.chapter).append("] ")
                sb.append(m.desc).append("\n")
            }
        }
        sb.append("\n")
    }

    private fun appendEmotions(
        sb: StringBuilder,
        emotions: List<SessionOutlineItem>,
        roleIdToName: Map<String, String>,
    ) {
        val active = emotions.filter { it.selected }
        if (active.isEmpty()) return
        sb.append("【感情线】\n")
        for (e in active) {
            val meta = StoryMeta.parseEmotion(e.metaJson)
            val a = roleNameOrFallback(meta.roleIdA, roleIdToName)
            val b = roleNameOrFallback(meta.roleIdB, roleIdToName)
            sb.append("- ").append(a).append(" ↔ ").append(b)
                .append("：").append(meta.stage.ifBlank { "未定义" })
            if (meta.progress != 0) sb.append("（").append(meta.progress.coerceIn(0, 100)).append("%）")
            val note = e.content.trim()
            if (note.isNotEmpty()) sb.append(" — ").append(note)
            sb.append("\n")
        }
        sb.append("\n")
    }

    private fun appendForeshadows(sb: StringBuilder, foreshadows: List<SessionOutlineItem>) {
        val active = foreshadows.filter { it.selected }
        if (active.isEmpty()) return
        val byState = active.groupBy {
            StoryMeta.parseForeshadow(it.metaJson).state.ifBlank { "planted" }
        }
        sb.append("【伏笔池】\n")
        for ((stateKey, label) in listOf(
            "planted" to "已埋下",
            "developing" to "铺垫中",
            "paid_off" to "已回收",
        )) {
            val list = byState[stateKey] ?: continue
            sb.append("## ").append(label).append("\n")
            for (f in list) {
                val meta = StoryMeta.parseForeshadow(f.metaJson)
                val title = f.title.trim().ifEmpty { "(无标题)" }
                sb.append("- ").append(title)
                if (meta.plantedChapter.isNotBlank()) sb.append(" [埋: ").append(meta.plantedChapter).append("]")
                if (stateKey == "paid_off" && meta.paidOffChapter.isNotBlank())
                    sb.append(" [收: ").append(meta.paidOffChapter).append("]")
                if (meta.halfLife.isNotBlank() && meta.halfLife != "medium")
                    sb.append(" (节奏=").append(halfLifeLabel(meta.halfLife)).append(")")
                if (f.content.isNotBlank()) sb.append(" — ").append(f.content.trim())
                sb.append("\n")
            }
        }
        // 未匹配 state 的, 显示成"未分类"
        val unknown = active.filter {
            val s = StoryMeta.parseForeshadow(it.metaJson).state
            s.isBlank() || s !in setOf("planted", "developing", "paid_off")
        }
        if (unknown.isNotEmpty()) {
            sb.append("## 未分类\n")
            for (f in unknown) {
                val t = f.title.trim().ifEmpty { "(无标题)" }
                sb.append("- ").append(t)
                if (f.content.isNotBlank()) sb.append(" — ").append(f.content.trim())
                sb.append("\n")
            }
        }
        sb.append("\n")
    }

    private fun halfLifeLabel(h: String): String = when (h) {
        "short" -> "短"
        "medium" -> "中"
        "long" -> "长"
        "endgame" -> "终局"
        else -> h
    }

    private fun appendStatuses(
        sb: StringBuilder,
        statuses: List<SessionOutlineItem>,
        roleIdToName: Map<String, String>,
    ) {
        val active = statuses.filter { it.selected }
        if (active.isEmpty()) return
        sb.append("【状态卡（current 状态；历史在服务端，本段只取最新）】\n")
        val grouped = active.groupBy {
            StoryMeta.parseStatus(it.metaJson).ownerRoleId
        }
        for ((ownerId, list) in grouped) {
            val ownerName = if (ownerId.isBlank()) "(全局)" else roleNameOrFallback(ownerId, roleIdToName)
            sb.append("## ").append(ownerName).append("\n")
            for (s in list) {
                val meta = StoryMeta.parseStatus(s.metaJson)
                val card = s.title.trim().ifEmpty { "状态" }
                val cur = meta.current.trim().ifEmpty { s.content.trim() }.ifEmpty { "(空)" }
                sb.append("- ").append(card).append(": ").append(cur).append("\n")
            }
        }
        sb.append("\n")
    }

    private fun roleNameOrFallback(roleId: String, map: Map<String, String>): String {
        if (roleId.isBlank()) return "(未指定)"
        return map[roleId] ?: "(已删除)"
    }

    // ───────────────── Legacy helpers ─────────────────

    private fun appendTitledSection(sb: StringBuilder, label: String, items: List<SessionOutlineItem>) {
        val visible = items.filter { it.selected }
        if (visible.isEmpty()) return
        sb.append("【").append(label).append("】\n")
        for (it in visible) {
            val title = it.title.trim()
            val content = it.content.trim()
            if (title.isEmpty() && content.isEmpty()) continue
            sb.append("- ")
            if (title.isNotEmpty()) sb.append(title).append("：")
            sb.append(content.ifEmpty { "(无内容)" })
            sb.append("\n")
        }
        sb.append("\n")
    }

    private fun appendUntitledSection(sb: StringBuilder, label: String, items: List<SessionOutlineItem>) {
        val visible = items.filter { it.selected }
        if (visible.isEmpty()) return
        sb.append("【").append(label).append("】\n")
        for (it in visible) {
            val content = it.content.trim()
            if (content.isEmpty()) continue
            sb.append("- ").append(content).append("\n")
        }
        sb.append("\n")
    }

    private fun indentLines(text: String, indent: String): String {
        return text.split("\n").joinToString("\n") { indent + it }
    }

    /**
     * 在构建好的大纲文本末尾追加 outlinePrompt（文风/风格指导）。
     * 如果 outlinePrompt 为空则原样返回。
     */
    fun appendOutlinePrompt(outlineText: String, outlinePrompt: String?): String {
        val prompt = outlinePrompt?.trim().orEmpty()
        if (prompt.isEmpty()) return outlineText
        val sb = StringBuilder(outlineText)
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append("【文风与风格指导】\n").append(prompt)
        return sb.toString()
    }

    /** 计算"应该建议生成卷纲"的判定：总章节数 > 15 或 总字数 > 2000。 */
    fun shouldSuggestVolume(items: List<SessionOutlineItem>): Boolean {
        val chapters = items.filter { it.type == StoryTypes.CHAPTER && it.selected }
        if (chapters.size > 15) return true
        val totalChars = chapters.sumOf { it.content.length }
        return totalChars > 2000
    }
}
