package com.example.aichat.sync

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把 wi-chat-server `/api/tool/memory-recall` 返回的 raw JSON 转成 LLM 友好的纯文本.
 *
 * 现象: DeepSeek V3.2 等模型把整个 server JSON 当 tool result 读时, 经常
 * 抓不到 memories[].content, 直接生成跟 result 无关的回复. 转成结构化纯文本后
 * 模型直接看到"找到 N 条相关记忆: 1. ... 2. ...", 命中率明显提升.
 *
 * Raw 仍然写到 ToolCallLog 用于调试 — 这里只动喂给 LLM 的版本.
 */
object SearchMemoryFormatter {

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun format(rawJson: String): String {
        if (rawJson.isBlank()) return rawJson
        return try {
            val root = JsonParser().parse(rawJson).asJsonObject
            val ok = readBool(root, "ok", true)
            if (!ok) {
                // server 报错, 直接把原文给模型看, 它自己处理
                return rawJson
            }
            val countDeclared = readInt(root, "count")
            val memoriesEl = root.get("memories")
            if (memoriesEl == null || memoriesEl.isJsonNull || !memoriesEl.isJsonArray) {
                return "search_memory: count=$countDeclared, no memories returned."
            }
            val arr = memoriesEl.asJsonArray
            if (arr.size() == 0) {
                return "search_memory: count=0, no relevant memory found. " +
                    "Tell the user there is no record for this query. Do NOT fabricate."
            }
            buildString {
                append("search_memory found ").append(arr.size()).append(" memories")
                val q = readStr(root, "query")
                if (q.isNotEmpty()) append(" for query \"").append(q).append("\"")
                append(":\n")
                for ((i, el) in arr.withIndex()) {
                    if (el == null || el.isJsonNull || !el.isJsonObject) continue
                    val o = el.asJsonObject
                    val content = readStr(o, "content").trim()
                    if (content.isEmpty()) continue
                    val mtype = readStr(o, "memoryType").ifEmpty { "?" }
                    val createdAt = readLong(o, "createdAt")
                    val score = readDouble(o, "score")
                    append((i + 1)).append(". [").append(mtype)
                    if (createdAt > 0) {
                        append(", ").append(tsFmt.format(Date(createdAt)))
                    }
                    if (score > 0.0) {
                        append(", score ").append(String.format(Locale.US, "%.2f", score))
                    }
                    append("] ").append(content).append('\n')
                    appendFactsIfAny(o.get("facts"))
                }
            }.trimEnd()
        } catch (_: Exception) {
            // 解析失败时把原文给模型, 让它自己判断
            rawJson
        }
    }

    private fun StringBuilder.appendFactsIfAny(facts: JsonElement?) {
        if (facts == null || facts.isJsonNull || !facts.isJsonArray) return
        val arr = facts.asJsonArray
        if (arr.size() == 0) return
        for (f in arr) {
            if (f == null || f.isJsonNull || !f.isJsonObject) continue
            val o = f.asJsonObject
            val k = readStr(o, "key")
            val v = readStr(o, "value")
            if (k.isEmpty() || v.isEmpty()) continue
            append("   - fact: ").append(k).append("=").append(v).append('\n')
        }
    }

    private fun readStr(obj: JsonObject, key: String): String {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) "" else try { e.asString } catch (_: Exception) { "" }
    }

    private fun readInt(obj: JsonObject, key: String): Int {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) 0 else try { e.asInt } catch (_: Exception) { 0 }
    }

    private fun readLong(obj: JsonObject, key: String): Long {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) 0L else try { e.asLong } catch (_: Exception) { 0L }
    }

    private fun readDouble(obj: JsonObject, key: String): Double {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) 0.0 else try { e.asDouble } catch (_: Exception) { 0.0 }
    }

    private fun readBool(obj: JsonObject, key: String, default: Boolean): Boolean {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) default else try { e.asBoolean } catch (_: Exception) { default }
    }
}
