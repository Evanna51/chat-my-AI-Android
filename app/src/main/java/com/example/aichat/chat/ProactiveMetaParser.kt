package com.example.aichat.chat

import com.google.gson.JsonParser

/**
 * Parses the `<<<META ... META>>>` tail block emitted by models in 自动对话模式.
 *
 * Design notes:
 * - 容错优先: 模型经常忘 close 标签 / 加多余空白 / 用 ``` 包 JSON. 我们尽量从混乱中抢救出 JSON.
 * - clamp 数值边界: afterSec 钳到 [30, 600], split 最多 5 段 (硬上限避免刷屏).
 * - 解析失败一律返回 null meta + 原 content, 不抛异常.
 *
 * Marker 用 `<<<META` / `META>>>` 而不是 XML/JSON 嵌入, 因为:
 *   - 不容易和正文里的 `<` / `{` 误碰
 *   - 即便流式输出半截到达, 也能以 `<<<META` 为锚点
 */
object ProactiveMetaParser {

    private const val OPEN_TAG = "<<<META"
    private const val CLOSE_TAG = "META>>>"

    private const val MIN_FOLLOWUP_SEC = 30
    private const val MAX_FOLLOWUP_SEC = 600
    private const val MAX_SPLIT_PARTS = 5
    private const val MAX_INTENT_LEN = 80

    /**
     * 从模型完整回复中提取 META 块.
     * 返回 cleanContent (可显示) + meta (可 null).
     *
     * 行为:
     * - 找不到 OPEN_TAG: cleanContent = raw.trim(), meta = null
     * - 找到 OPEN_TAG 但缺 CLOSE_TAG: 视作模型截断, cleanContent = OPEN 之前的部分, meta = null
     * - 找到完整块但 JSON 解析失败: cleanContent = OPEN 之前的部分, meta = null
     * - 全部成功: cleanContent = OPEN 之前的 trim, meta = 解析后的对象
     */
    @JvmStatic
    fun extract(raw: String?): ProactiveMetaExtractResult {
        if (raw == null || raw.isEmpty()) {
            return ProactiveMetaExtractResult("", null)
        }
        val openIdx = raw.indexOf(OPEN_TAG)
        if (openIdx < 0) {
            return ProactiveMetaExtractResult(raw.trimEnd(), null)
        }
        val cleanContent = raw.substring(0, openIdx).trimEnd()
        val closeIdx = raw.indexOf(CLOSE_TAG, openIdx + OPEN_TAG.length)
        if (closeIdx < 0) {
            // 模型截断 META; 抢救 cleanContent, 但 meta 不可用
            return ProactiveMetaExtractResult(cleanContent, null)
        }
        val jsonRaw = raw.substring(openIdx + OPEN_TAG.length, closeIdx)
        val meta = parseMetaJson(jsonRaw)
        return ProactiveMetaExtractResult(cleanContent, meta)
    }

    /** Strip wrapping fences/whitespace and JsonParse. Returns null on any failure. */
    private fun parseMetaJson(rawBlock: String): ProactiveMeta? {
        val cleaned = stripFences(rawBlock).trim()
        if (cleaned.isEmpty()) return null
        return try {
            @Suppress("DEPRECATION")
            val element = JsonParser().parse(cleaned)
            if (!element.isJsonObject) return null
            val obj = element.asJsonObject

            val split = parseSplit(obj)
            val followUp = parseFollowUp(obj)
            val autoStop = parseAutoStop(obj)
            // 都为 null = 模型显式表态都不要; 仍然返回 meta 对象, 让上游知道协议被遵守了.
            ProactiveMeta(split = split, followUp = followUp, autoStop = autoStop)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSplit(obj: com.google.gson.JsonObject): List<String>? {
        if (!obj.has("split")) return null
        val raw = obj.get("split")
        if (raw == null || raw.isJsonNull) return null
        if (!raw.isJsonArray) return null
        val arr = raw.asJsonArray
        if (arr.size() == 0) return null
        val out = ArrayList<String>(minOf(arr.size(), MAX_SPLIT_PARTS))
        for (i in 0 until arr.size()) {
            if (out.size >= MAX_SPLIT_PARTS) break
            val item = arr.get(i)
            if (item == null || item.isJsonNull) continue
            val s = if (item.isJsonPrimitive) item.asString else item.toString()
            val trimmed = s?.trim().orEmpty()
            if (trimmed.isNotEmpty()) out.add(trimmed)
        }
        return if (out.isEmpty()) null else out
    }

    private fun parseAutoStop(obj: com.google.gson.JsonObject): Boolean {
        if (!obj.has("autoStop")) return false
        return try {
            val raw = obj.get("autoStop")
            if (raw == null || raw.isJsonNull) false
            else if (raw.isJsonPrimitive) raw.asBoolean
            else false
        } catch (_: Exception) { false }
    }

    private fun parseFollowUp(obj: com.google.gson.JsonObject): ProactiveFollowUp? {
        if (!obj.has("followUp")) return null
        val raw = obj.get("followUp")
        if (raw == null || raw.isJsonNull) return null
        if (!raw.isJsonObject) return null
        val fu = raw.asJsonObject
        val afterRaw = try {
            if (fu.has("afterSec") && fu.get("afterSec").isJsonPrimitive)
                fu.get("afterSec").asInt else MIN_FOLLOWUP_SEC
        } catch (_: Exception) { MIN_FOLLOWUP_SEC }
        val after = afterRaw.coerceIn(MIN_FOLLOWUP_SEC, MAX_FOLLOWUP_SEC)
        val intentRaw = try {
            if (fu.has("intent") && fu.get("intent").isJsonPrimitive)
                fu.get("intent").asString else ""
        } catch (_: Exception) { "" }
        val intent = intentRaw.trim().take(MAX_INTENT_LEN)
        return ProactiveFollowUp(afterSec = after, intent = intent)
    }

    /** 模型偶尔把 JSON 包在 ```json ... ``` 里, 也可能加 BOM/空行. */
    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            // ```json or ``` then content then ```
            val firstNl = t.indexOf('\n')
            if (firstNl >= 0) t = t.substring(firstNl + 1)
            if (t.endsWith("```")) t = t.substring(0, t.length - 3)
            t = t.trim()
        }
        return t
    }
}
