package com.example.aichat.inkos

import com.example.aichat.SessionOutlineItem

/**
 * 把 app 的 outline 数据 + per-session 配置按 **inkos architect** 期望的 5-段 SECTION
 * schema 预拼成 blurb,发给 `/api/v1/books/create`。
 *
 * 设计原则:
 *  - architect 已经把 `book.genre` 当作题材底色注入了它的系统提示, 所以 story_frame 散文
 *    我们不强行拼,只在用户大纲里有 `world` 条目时作为 user_hint 附加。
 *  - book_rules 不再从 `knowledge` 推 — 而是来自 per-session 的 [bookRulesYaml]
 *    (默认走 [InkosSubtypePresets] 的子类模板, 用户可改)。
 *  - `knowledge` (知情约束) 划归 pending_hooks, 作 startChapter=0 的初始叙事边界钩子。
 *
 * 类型映射 (改后):
 *   story_frame  ← (空, architect 自己写) + 可选 `world` user_hint
 *   volume_map   ← `volume` + `chapter`
 *   roles        ← `task`
 *   book_rules   ← per-session inkosBookRulesYaml
 *   pending_hooks ← `material` + `knowledge` (后者作为 startChapter=0 初始边界)
 */
object InkosBlurbBuilder {

    fun build(
        items: List<SessionOutlineItem>,
        title: String,
        bookRulesYaml: String,
    ): String {
        val worlds = items.filter { it.type == "world" }
        val volumes = items.filter { it.type == "volume" }
        val chapters = items.filter { it.type == "chapter" }
        val roles = items.filter { it.type == "task" }
        val knowledge = items.filter { it.type == "knowledge" }
        val materials = items.filter { it.type == "material" }

        val sb = StringBuilder()
        sb.append("以下是用户已整理好的基础设定 (按 inkos 的 5 段 SECTION 预拼)。\n")
        sb.append("**请直接采用以下内容**:\n")
        sb.append(" 1. 每段按 architect 系统提示的字数 / 散文密度展开\n")
        sb.append(" 2. 严格按 `=== SECTION: xxx ===` 分块输出 5 段, 缺一不可\n")
        sb.append(" 3. 缺材料的 section 按系统提示与题材底色自行补全, 但保留用户已提供的语义\n")
        sb.append(" 4. book_rules 段必须**直接采用以下 YAML**, 仅在 protagonist 块按 roles 内容补全\n\n")

        // story_frame: 不硬拼。仅当 world 不为空时作 user_hint 注入。
        sb.append("=== SECTION: story_frame ===\n")
        sb.append(buildStoryFrameHints(worlds, title))
        sb.append("\n\n")

        sb.append("=== SECTION: volume_map ===\n")
        sb.append(buildVolumeMap(volumes, chapters))
        sb.append("\n\n")

        sb.append("=== SECTION: roles ===\n")
        sb.append(buildRoles(roles))
        sb.append("\n\n")

        // book_rules: 直接用 per-session YAML, architect 只允许在 protagonist 块补全。
        sb.append("=== SECTION: book_rules ===\n")
        sb.append(buildBookRules(bookRulesYaml))
        sb.append("\n\n")

        sb.append("=== SECTION: pending_hooks ===\n")
        sb.append(buildPendingHooks(materials, knowledge))
        sb.append("\n")

        return sb.toString().trim()
    }

    // ─────────── per-section ───────────

    private fun buildStoryFrameHints(worlds: List<SessionOutlineItem>, title: String): String {
        if (worlds.isEmpty()) {
            return "(用户未提供 world 设定 — 请按 inkos 题材底色 + 书名《$title》自行构想 4 段散文: 主题与基调 / 核心冲突与前台-后台双层 / 世界观底色 / 终局方向与全书 Objective。)"
        }
        val sb = StringBuilder()
        sb.append("以下是用户提供的 world / 设定 hint, 请融入 4 段散文中:\n\n")
        for (w in worlds) {
            val t = w.title.trim()
            val c = w.content.trim()
            if (t.isEmpty() && c.isEmpty()) continue
            sb.append("- ")
            if (t.isNotEmpty()) sb.append(t).append(": ")
            sb.append(c).append("\n")
        }
        sb.append("\n请按 architect 系统提示扩写为 4 段散文。")
        return sb.toString()
    }

    private fun buildVolumeMap(
        volumes: List<SessionOutlineItem>,
        chapters: List<SessionOutlineItem>,
    ): String {
        if (volumes.isEmpty() && chapters.isEmpty()) {
            return "(用户未提供卷/章大纲 — 请按目标章数与题材底色自行排卷, 产出 5 段 + 节奏原则尾段。)"
        }
        val sb = StringBuilder()
        if (volumes.isNotEmpty()) {
            sb.append("## 用户提供的卷大纲\n\n")
            for ((i, v) in volumes.withIndex()) {
                val t = v.title.trim().ifEmpty { "卷 ${i + 1}" }
                sb.append("### 卷 ${i + 1}: ").append(t).append("\n")
                if (v.content.isNotBlank()) sb.append(v.content.trim()).append("\n")
                val cov = v.volumeChapters.filter { it.isNotBlank() }
                if (cov.isNotEmpty()) sb.append("覆盖章节: ").append(cov.joinToString("、")).append("\n")
                sb.append("\n")
            }
        }
        if (chapters.isNotEmpty()) {
            sb.append("## 用户提供的章节大纲 (供卷规划参考, 不要照搬到 volume_map 章级)\n\n")
            for ((i, c) in chapters.withIndex()) {
                val t = c.title.trim().ifEmpty { "ch.${i + 1}" }
                sb.append(i + 1).append(". ").append(t)
                if (c.content.isNotBlank()) sb.append(" — ").append(c.content.trim().take(120))
                sb.append("\n")
            }
        }
        sb.append("\n请基于以上拓展为 5 段 volume_map: 各卷主题与情绪曲线 / 卷间钩子 / 各卷 OKR / 卷尾必须发生的改变 / 节奏原则。")
        return sb.toString()
    }

    private fun buildRoles(roles: List<SessionOutlineItem>): String {
        if (roles.isEmpty()) {
            return "(用户未提供人物资料 — 请基于 story_frame 主题与冲突自行设计主要/次要角色, 按 ---ROLE--- 一人一卡输出。)"
        }
        val sb = StringBuilder()
        sb.append("以下是用户提供的人物资料。**第一个**作为主角(tier=major),其余按重要性 major/minor 自行判断。\n")
        sb.append("请按 architect 要求的 `---ROLE---\\ntier:\\nname:\\n---CONTENT---\\n` 格式逐个输出。\n\n")
        for ((i, r) in roles.withIndex()) {
            val tier = if (i == 0) "major" else "minor"
            val name = r.title.trim().ifEmpty { "角色${i + 1}" }
            sb.append("---ROLE---\n")
            sb.append("tier: ").append(tier).append("\n")
            sb.append("name: ").append(name).append("\n")
            sb.append("---CONTENT---\n")
            if (r.content.isNotBlank()) {
                sb.append("(用户原始描述: ").append(r.content.trim()).append(")\n")
                sb.append("请基于上述描述补全 architect 要求的小标题段落:\n")
            } else {
                sb.append("(用户仅提供姓名, 请基于 story_frame 自行设计性格/过往/关系)\n")
            }
            sb.append("## 核心标签\n## 反差细节\n## 人物小传\n")
            if (i == 0) sb.append("## 主角弧线\n")
            sb.append("## 当前现状\n## 关系网络\n## 内在驱动\n## 成长弧光\n\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * 直接复用 per-session YAML。**包在 ``` 围栏内**,architect 看到围栏会按字面采纳。
     * `protagonist` 字段如果用户没填,留 architect 据 roles 第一个角色补全。
     */
    private fun buildBookRules(bookRulesYaml: String): String {
        val yaml = bookRulesYaml.trim().ifEmpty { InkosSubtypePresets.DEFAULT.defaultBookRulesYaml }
        // 在 YAML 前后包围栏 + 加说明,避免 architect 把 YAML 当成散文来重写。
        val sb = StringBuilder()
        sb.append("**以下 YAML 由用户在 app 端预先配置, 请字面采用, 仅在 protagonist 段缺失时按 roles 第一个角色补全:**\n\n")
        sb.append("```yaml\n")
        sb.append(yaml).append("\n")
        sb.append("```")
        return sb.toString()
    }

    private fun buildPendingHooks(
        materials: List<SessionOutlineItem>,
        knowledge: List<SessionOutlineItem>,
    ): String {
        if (materials.isEmpty() && knowledge.isEmpty()) {
            return "(用户未提供初始钩子 — architect 请按 story_frame 设计 3-5 条 startChapter=0 的初始钩子, 含 Phase 7 扩展列。)"
        }
        val sb = StringBuilder()
        sb.append("以下是用户提供的初始钩子与知情边界, 请整理成 Phase 7 表格 (startChapter=0, 含 depends_on / pays_off_in_arc / core_hook / half_life 等列):\n\n")
        var idx = 1
        for (m in materials) {
            val t = m.title.trim().ifEmpty { "hook_${idx}" }
            sb.append("- **H").append(String.format("%03d", idx)).append(" ").append(t).append("** (类型=资料/伏笔, startChapter=0): ")
            sb.append(m.content.trim().ifEmpty { "(无描述)" }).append("\n")
            idx++
        }
        for (k in knowledge) {
            val t = k.title.trim().ifEmpty { "boundary_${idx}" }
            sb.append("- **H").append(String.format("%03d", idx)).append(" ").append(t)
                .append("** (类型=知情边界, startChapter=0, 用作叙事约束): ")
            sb.append(k.content.trim().ifEmpty { "(无描述)" }).append("\n")
            idx++
        }
        sb.append("\n*知情边界* 类钩子的回收节奏建议填 `慢烧` 或 `终局`, core_hook 看角色身份动机判断。")
        return sb.toString()
    }
}
