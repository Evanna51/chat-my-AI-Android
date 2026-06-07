package com.example.aichat

/**
 * 把 outline 条目按类型分组、应用 selected 与 volume 覆盖逻辑后，输出结构化 prompt 段落。
 *
 * 任何需要往 LLM prompt 注入大纲的场景都应该走这里，避免再出现"按时间顺序混合标 type"的拼法。
 *
 * 分组顺序固定：卷大纲 → 章节大纲 → 人物 → 世界 → 知情约束 → 资料。
 *
 * Volume 覆盖逻辑：
 * - 收集所有 selected=true 的 volume，把它们的 volumeChapters 取并集 → coveredTitles
 * - 章节遍历时，若 chapter.title 在 coveredTitles 中且该 chapter selected=true，则被跳过
 *   （已有卷纲代表它）
 * - selected=false 的 volume 自身不输出
 */
object OutlinePromptBuilder {

    /**
     * @param items 全部 outline 条目（任意顺序，内部按 createdAt 已经排好）
     * @param includeKnowledgeEnforcement true 时在知情约束段后追加"角色只能用已知信息"等强约束句
     * @return 结构化大纲文本；若 outline 完全为空（去掉 deselected 之后），返回空串
     */
    fun build(
        items: List<SessionOutlineItem>,
        includeKnowledgeEnforcement: Boolean = true,
    ): String {
        if (items.isEmpty()) return ""

        // 分组
        val volumes = items.filter { it.type == "volume" }
        val chapters = items.filter { it.type == "chapter" }
        val tasks = items.filter { it.type == "task" }
        val worlds = items.filter { it.type == "world" }
        val knowledge = items.filter { it.type == "knowledge" }
        val materials = items.filter { it.type == "material" }

        // volume 覆盖集
        val activeVolumes = volumes.filter { it.selected }
        val coveredTitles: Set<String> = activeVolumes
            .flatMap { it.volumeChapters }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val sb = StringBuilder()

        // 卷大纲
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

        // 章节大纲（去掉被卷纲覆盖的，去掉 deselected）
        val visibleChapters = chapters.filter {
            it.selected && (it.title.trim().isEmpty() || it.title.trim() !in coveredTitles)
        }
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
            sb.append("\n")
        }

        // 人物
        appendTitledSection(sb, "人物资料", tasks)

        // 世界
        appendUntitledSection(sb, "世界背景", worlds)

        // 知情约束
        appendTitledSection(sb, "知情约束", knowledge)
        if (includeKnowledgeEnforcement && knowledge.any { it.selected }) {
            sb.append("\n知情边界硬约束：\n")
            sb.append("1) 角色只能基于其已知信息行动、发言与推理；\n")
            sb.append("2) 未知信息不得被角色提及或据此决策；\n")
            sb.append("3) 让角色得知信息须先写出获取路径（目击/对话/文件/推理）；\n")
            sb.append("4) 不要把读者已知当作角色已知。\n\n")
        }

        // 资料
        appendUntitledSection(sb, "其他资料", materials)

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
        val chapters = items.filter { it.type == "chapter" && it.selected }
        if (chapters.size > 15) return true
        val totalChars = chapters.sumOf { it.content.length }
        return totalChars > 2000
    }
}
