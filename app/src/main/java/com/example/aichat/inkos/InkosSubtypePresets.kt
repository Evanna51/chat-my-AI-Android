package com.example.aichat.inkos

/**
 * inkos 建书时的子类预设 — 把 (genre id, 默认 book_rules YAML) 绑成一组。
 *
 * 三个 custom zh genre 对应风格(在 inkos studio 里维护 chapterTypes / fatigueWords /
 * pacingRule / body, app 不重复定义这些):
 *   - chunxiao-erotic   (春宵札记)  文学功底 + 肉欲堆砌, 不限设定
 *   - classical-erotic  (通俗夜话)  通俗易读 / 大众向, 不限设定
 *   - mingqing-erotic   (巷陌闺词)  明清白话语感, 仿金瓶梅 / 肉蒲团
 *
 * **关键**: book_rules YAML 是 architect 在每本书上叠加的 per-book 元数据。
 * 题材级的禁忌 / 语言铁律 / 章节类型 / 疲劳词 全部在 inkos genre body 里维护
 * (chapterTypes / fatigueWords / pacingRule / 题材禁忌 / 语言铁律), app 这边的
 * `prohibitions` 留空, 避免把题材级约束硬绑到每本书上影响大纲准确性。
 *
 * `protagonist` 块由 architect 据 roles 第一个角色自动补全。
 *
 * 用户在「会话设置 → Ink 建书设置」可改 book_rules YAML;`reset` 按钮恢复模板。
 */
object InkosSubtypePresets {

    data class Preset(
        val id: String,
        val displayName: String,
        val genreId: String,
        val defaultBookRulesYaml: String,
    )

    /**
     * 极简 YAML 模板:只锁 genre, 其它全留给 inkos genre 默认 + architect 补全。
     */
    private fun minimalYaml(genreId: String): String = """
        version: "1.0"
        genreLock:
          primary: $genreId
          forbidden: []
        prohibitions: []
        chapterTypesOverride: []
        fatigueWordsOverride: []
        additionalAuditDimensions: []
        enableFullCastTracking: false
    """.trimIndent()

    val ALL: List<Preset> = listOf(
        Preset(
            id = "default",
            displayName = "通用 (走 inkos other)",
            genreId = "other",
            defaultBookRulesYaml = minimalYaml("other"),
        ),
        Preset(
            id = "chunxiao-erotic",
            displayName = "春宵札记 (文学性 + 肉欲堆砌)",
            genreId = "chunxiao-erotic",
            defaultBookRulesYaml = minimalYaml("chunxiao-erotic"),
        ),
        Preset(
            id = "classical-erotic",
            displayName = "通俗夜话 (通俗易读 / 大众向)",
            genreId = "classical-erotic",
            defaultBookRulesYaml = minimalYaml("classical-erotic"),
        ),
        Preset(
            id = "mingqing-erotic",
            displayName = "巷陌闺词 (明清白话 / 古典)",
            genreId = "mingqing-erotic",
            defaultBookRulesYaml = minimalYaml("mingqing-erotic"),
        ),
    )

    val DEFAULT: Preset = ALL[0]

    /**
     * 按 id 查;找不到走 DEFAULT。
     * 兼容历史 id (palace-erotic → classical-erotic, urban-erotic → chunxiao-erotic):
     * 给曾选过旧子类的会话自动迁移到新 id 对应的 preset。
     */
    fun byId(id: String?): Preset {
        if (id.isNullOrBlank()) return DEFAULT
        val mapped = when (id) {
            "palace-erotic" -> "classical-erotic"
            "urban-erotic" -> "chunxiao-erotic"
            else -> id
        }
        return ALL.firstOrNull { it.id == mapped } ?: DEFAULT
    }
}
