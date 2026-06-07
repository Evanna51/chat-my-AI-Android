package com.example.aichat.story

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * type-specific metaJson schema 定义与读写。
 *
 * 各类型用独立 data class 包装,toJson / fromJson 走 Gson;空 / 异常一律退回默认对象,
 * 不让 outline 因脏数据炸掉。
 */
object StoryMeta {

    private val GSON = Gson()

    // ──────────────── roles ────────────────

    data class RoleMeta(
        val tier: String = "minor",            // major / minor / extra
        val tags: List<String> = emptyList(),
        val appearance: String = "",           // 反差细节 / 外观
        val personality: String = "",          // 核心标签 / 性格
        val background: String = "",           // 人物小传
        val motivation: String = "",           // 内在驱动
        val arc: String = ""                   // 成长弧光 / 主角弧线
    )

    fun parseRole(meta: String?): RoleMeta = parse(meta, RoleMeta::class.java, RoleMeta())
    fun toJson(m: RoleMeta): String = GSON.toJson(m)

    // ──────────────── foreshadow ────────────────

    data class ForeshadowMeta(
        val state: String = "planted",         // planted / developing / paid_off
        val plantedChapter: String = "",
        val paidOffChapter: String = "",
        val halfLife: String = "medium"        // short / medium / long / endgame
    )

    fun parseForeshadow(meta: String?): ForeshadowMeta =
        parse(meta, ForeshadowMeta::class.java, ForeshadowMeta())

    fun toJson(m: ForeshadowMeta): String = GSON.toJson(m)

    // ──────────────── status ────────────────
    // history 不本地存,走 wi-server。

    data class StatusMeta(
        val ownerRoleId: String = "",
        val current: String = ""
    )

    fun parseStatus(meta: String?): StatusMeta = parse(meta, StatusMeta::class.java, StatusMeta())
    fun toJson(m: StatusMeta): String = GSON.toJson(m)

    // ──────────────── relation ────────────────

    data class RelationMeta(
        val fromRoleId: String = "",
        val toRoleId: String = "",
        val kind: String = "",                 // 亲缘 / 敌对 / 暧昧 / 师徒 ...
        val level: Int = 0,                    // -100..100
        val note: String = ""
    )

    fun parseRelation(meta: String?): RelationMeta =
        parse(meta, RelationMeta::class.java, RelationMeta())

    fun toJson(m: RelationMeta): String = GSON.toJson(m)

    // ──────────────── subplot ────────────────

    data class SubplotMilestone(
        val chapter: String = "",
        val desc: String = "",
        val done: Boolean = false
    )

    data class SubplotMeta(
        val progress: Int = 0,                 // 0..100
        val milestones: List<SubplotMilestone> = emptyList(),
        val linkedRoleIds: List<String> = emptyList()
    )

    fun parseSubplot(meta: String?): SubplotMeta =
        parse(meta, SubplotMeta::class.java, SubplotMeta())

    fun toJson(m: SubplotMeta): String = GSON.toJson(m)

    // ──────────────── emotion ────────────────

    data class EmotionMeta(
        val roleIdA: String = "",
        val roleIdB: String = "",
        val stage: String = "陌生",            // 陌生 / 试探 / 靠近 / 亲密 / 破裂 / 和解 / 分别
        val progress: Int = 0
    )

    fun parseEmotion(meta: String?): EmotionMeta =
        parse(meta, EmotionMeta::class.java, EmotionMeta())

    fun toJson(m: EmotionMeta): String = GSON.toJson(m)

    // ──────────────── rules ────────────────
    // 字段级编辑,app 拼回 YAML 注入 prompt。customYaml 是老 inkos YAML 迁移逃生口。

    data class RulesMeta(
        val protagonist: String = "",          // 主角设定一句话
        val tone: String = "",                 // 基调
        val pov: String = "",                  // 视角 first / third-limited / third-omniscient
        val tense: String = "",                // 时态 past / present
        val taboos: List<String> = emptyList(),
        val styleRefs: List<String> = emptyList(),
        val customYaml: String = ""            // 老数据迁移保底
    )

    fun parseRules(meta: String?): RulesMeta = parse(meta, RulesMeta::class.java, RulesMeta())
    fun toJson(m: RulesMeta): String = GSON.toJson(m)

    /**
     * 把 RulesMeta 拼成 YAML 字符串,发给 LLM 时直接用。
     * 不引入 YAML 库 —— 简单 K-V 手写,字段名跟 inkos book_rules 对齐。
     */
    fun buildRulesYaml(m: RulesMeta): String {
        val sb = StringBuilder()
        if (m.protagonist.isNotBlank()) sb.append("protagonist: ").append(yamlEscape(m.protagonist)).append("\n")
        if (m.tone.isNotBlank())        sb.append("tone: ").append(yamlEscape(m.tone)).append("\n")
        if (m.pov.isNotBlank())         sb.append("pov: ").append(yamlEscape(m.pov)).append("\n")
        if (m.tense.isNotBlank())       sb.append("tense: ").append(yamlEscape(m.tense)).append("\n")
        if (m.taboos.isNotEmpty()) {
            sb.append("taboos:\n")
            for (t in m.taboos) if (t.isNotBlank()) sb.append("  - ").append(yamlEscape(t)).append("\n")
        }
        if (m.styleRefs.isNotEmpty()) {
            sb.append("style_refs:\n")
            for (s in m.styleRefs) if (s.isNotBlank()) sb.append("  - ").append(yamlEscape(s)).append("\n")
        }
        val custom = m.customYaml.trim()
        if (custom.isNotEmpty()) {
            sb.append("# --- legacy custom yaml (inkos migration) ---\n")
            sb.append(custom).append("\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * 反向解析: 接 inkos 旧 YAML 字符串,尽力从中拎出已知字段,拎不出来的整段塞 customYaml。
     */
    fun parseLegacyYaml(yaml: String?): RulesMeta {
        val raw = yaml?.trim().orEmpty()
        if (raw.isEmpty()) return RulesMeta()

        var protagonist = ""
        var tone = ""
        var pov = ""
        var tense = ""
        val taboos = mutableListOf<String>()
        val styleRefs = mutableListOf<String>()
        val unrecognized = StringBuilder()

        var currentList: MutableList<String>? = null
        for (rawLine in raw.split("\n")) {
            val line = rawLine.trimEnd()
            if (line.isEmpty() || line.trimStart().startsWith("#")) {
                currentList = null
                continue
            }
            // 列表项继续
            if (line.startsWith("  - ") || line.startsWith("- ")) {
                val item = line.substringAfter("- ").trim().trim('"')
                if (currentList != null && item.isNotEmpty()) {
                    currentList!!.add(item)
                    continue
                }
            }
            // K-V 或列表头
            val colon = line.indexOf(':')
            if (colon <= 0) {
                unrecognized.append(rawLine).append("\n")
                currentList = null
                continue
            }
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim().trim('"')
            when (key) {
                "protagonist" -> { protagonist = value; currentList = null }
                "tone"        -> { tone = value;       currentList = null }
                "pov"         -> { pov = value;        currentList = null }
                "tense"       -> { tense = value;      currentList = null }
                "taboos"      -> { currentList = if (value.isEmpty()) taboos else { taboos.add(value); null } }
                "style_refs"  -> { currentList = if (value.isEmpty()) styleRefs else { styleRefs.add(value); null } }
                else          -> { unrecognized.append(rawLine).append("\n"); currentList = null }
            }
        }
        return RulesMeta(
            protagonist = protagonist,
            tone = tone,
            pov = pov,
            tense = tense,
            taboos = taboos.toList(),
            styleRefs = styleRefs.toList(),
            customYaml = unrecognized.toString().trim()
        )
    }

    private fun yamlEscape(s: String): String {
        // 简单策略: 含冒号 / 井号 / 换行 / 起始空格的字符串加双引号 + 转义内部双引号
        val needsQuote = s.contains(':') || s.contains('#') || s.contains('\n')
            || s.startsWith(' ') || s.endsWith(' ')
        return if (needsQuote) "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        else s
    }

    private fun <T> parse(meta: String?, cls: Class<T>, fallback: T): T {
        val raw = meta?.trim().orEmpty()
        if (raw.isEmpty() || raw == "{}") return fallback
        return try {
            GSON.fromJson(raw, cls) ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    /**
     * 通用 patch: 把 patchJson 中的非空字段合并进 target metaJson。
     * 给 Story Tools 的 update_outline_item 用 —— 模型可以传 partial JSON。
     */
    fun patchMetaJson(targetJson: String, patchJson: String): String {
        val target = try {
            if (targetJson.isBlank()) JsonObject() else JsonParser().parse(targetJson).asJsonObject
        } catch (_: Throwable) { JsonObject() }
        val patch = try {
            if (patchJson.isBlank()) JsonObject() else JsonParser().parse(patchJson).asJsonObject
        } catch (_: Throwable) { return targetJson }
        for ((k, v) in patch.entrySet()) target.add(k, v)
        return target.toString()
    }
}

/** 所有合法 type 常量集中 —— 改 type 字面值时编译器报错处都能找到。 */
object StoryTypes {
    const val CHAPTER = "chapter"
    const val VOLUME = "volume"
    const val WORLD = "world"
    const val KNOWLEDGE = "knowledge"
    const val ROLES = "roles"
    const val FORESHADOW = "foreshadow"
    const val STATUS = "status"
    const val RELATION = "relation"
    const val SUBPLOT = "subplot"
    const val EMOTION = "emotion"
    const val RULES = "rules"

    val ALL: List<String> = listOf(
        CHAPTER, VOLUME, WORLD, KNOWLEDGE,
        ROLES, FORESHADOW, STATUS, RELATION, SUBPLOT, EMOTION, RULES
    )

    /** 老 type → 新 type。SessionOutlineStore 读时调用,做懒迁移。 */
    fun migrateLegacy(type: String?): String = when (type) {
        "task" -> ROLES
        "material" -> FORESHADOW
        null, "" -> CHAPTER
        else -> if (type in ALL) type else CHAPTER
    }

    fun isValid(type: String?): Boolean = type != null && type in ALL
}
