package com.example.aichat.chat

import com.google.gson.JsonParser

/**
 * V8 (2026-05-10): 双协议解析.
 *   1. 末尾 `||==FOLLOWUP==||{...}` / `||==STOP==||` / `||==SKIP==||` 元信息标记
 *      → 提取 followUp / autoStop / skip 决策, 从 raw 删除
 *   2. 剩余正文按 `\n\n+` 切分 → messages 数组
 *   3. 多段时 cleanContent = messages[0] (主气泡), split = 完整数组 (剩余段由
 *      ProactiveChatPlanner 逐条追加渲染)
 *
 * 容错:
 *   - SKIP 标记: messages 强制清空, 上层视作"放弃本次发言"
 *   - FOLLOWUP JSON 解析失败: 当作没传 followUp, 不影响其它字段
 *   - 多个段段超 [MAX_SPLIT_PARTS]: 截断保留前 N 段
 *   - 完全没 marker 也没 \n\n: 整条原文当 cleanContent (单段)
 */
object ProactiveMetaParser {

    private const val FOLLOWUP_PREFIX = "||==FOLLOWUP==||"
    private const val STOP_MARKER = "||==STOP==||"
    private const val SKIP_MARKER = "||==SKIP==||"
    private const val MAX_SPLIT_PARTS = 5
    private const val MIN_FOLLOWUP_SEC = 30
    private const val MAX_FOLLOWUP_SEC = 600
    private const val MAX_INTENT_LEN = 80
    // 与 ProactiveSplitStreamFilter 保持一致: 两个连续换行 (不允许中间有空格).
    // 之前用 \n\s*\n+ 允许空白行, 与 filter 的 "\n\n" 精确匹配不一致, 导致
    // LLM 输出 "\n \n" 时 filter 不触发 (流式照常显示后续段) 但 parser 却把它切开.
    private val PARAGRAPH_SEP = Regex("""\n\n+""")
    // 形如 ||==FOLLOWUP==||{"afterSec":120,"intent":"..."}
    private val FOLLOWUP_REGEX = Regex(
        """\|\|==FOLLOWUP==\|\|\s*(\{[^}]*\})""",
        RegexOption.DOT_MATCHES_ALL
    )

    @JvmStatic
    fun extract(raw: String?): ProactiveMetaExtractResult {
        if (raw.isNullOrEmpty()) return ProactiveMetaExtractResult("", null)
        var working = raw

        // 1) followUp: 先抽 (含 JSON 内容, 必须先于纯文本 STOP/SKIP 处理)
        var followUp: ProactiveFollowUp? = null
        val fuMatch = FOLLOWUP_REGEX.find(working)
        if (fuMatch != null) {
            followUp = parseFollowUpJson(fuMatch.groupValues[1])
            working = working.replaceRange(fuMatch.range, "")
        }

        // 2) skip: 强制清空 messages
        val isSkip = working.contains(SKIP_MARKER)
        if (isSkip) working = working.replace(SKIP_MARKER, "")

        // 3) autoStop
        val autoStop = working.contains(STOP_MARKER)
        if (autoStop) working = working.replace(STOP_MARKER, "")

        val body = working.trim()

        // 4) skip 路径: 不输出正文. SKIP 隐含 autoStop=true (终止追问链).
        //    meta 永远非 null —— 让上层区分"合法跳过" vs "LLM 输出空" 错误.
        if (isSkip) {
            return ProactiveMetaExtractResult(
                "",
                ProactiveMeta(null, followUp, autoStop = true)
            )
        }

        if (body.isEmpty()) {
            val meta = if (followUp != null || autoStop)
                ProactiveMeta(null, followUp, autoStop) else null
            return ProactiveMetaExtractResult("", meta)
        }

        // 5) 按 \n\n+ 切段 (空白 / tab / 多换行都算段间分隔)
        val parts = body.split(PARAGRAPH_SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_SPLIT_PARTS)

        return when {
            parts.isEmpty() -> {
                val meta = if (followUp != null || autoStop)
                    ProactiveMeta(null, followUp, autoStop) else null
                ProactiveMetaExtractResult("", meta)
            }
            parts.size == 1 -> {
                // 单段: 仅当有元信息时才生成 meta, 否则 meta=null (维持原"无协议"行为)
                val meta = if (followUp != null || autoStop)
                    ProactiveMeta(null, followUp, autoStop) else null
                ProactiveMetaExtractResult(parts[0], meta)
            }
            else -> {
                ProactiveMetaExtractResult(
                    parts[0],
                    ProactiveMeta(parts, followUp, autoStop)
                )
            }
        }
    }

    private fun parseFollowUpJson(jsonRaw: String): ProactiveFollowUp? {
        return try {
            @Suppress("DEPRECATION")
            val element = JsonParser().parse(jsonRaw.trim())
            if (!element.isJsonObject) return null
            val obj = element.asJsonObject
            val afterRaw = try {
                if (obj.has("afterSec") && obj.get("afterSec").isJsonPrimitive)
                    obj.get("afterSec").asInt else MIN_FOLLOWUP_SEC
            } catch (_: Exception) { MIN_FOLLOWUP_SEC }
            val after = afterRaw.coerceIn(MIN_FOLLOWUP_SEC, MAX_FOLLOWUP_SEC)
            val intentRaw = try {
                if (obj.has("intent") && obj.get("intent").isJsonPrimitive)
                    obj.get("intent").asString else ""
            } catch (_: Exception) { "" }
            val intent = intentRaw.trim().take(MAX_INTENT_LEN)
            ProactiveFollowUp(afterSec = after, intent = intent)
        } catch (_: Exception) { null }
    }
}
