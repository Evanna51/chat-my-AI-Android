package com.example.aichat.chat

import com.example.aichat.ChatApi
import com.example.aichat.chat.ChatJsonHelpers.firstNonEmpty
import com.example.aichat.chat.ChatJsonHelpers.getStringFlexible
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 从 ChatService 抽出的纯文本/JSON helper。无状态，所有方法都是 pure function。
 * 用途：被多个 ChatService.fun（chat / generateThreadTitle / generateSessionOutline /
 * summarizeMessageForOutline / generateChapterPlanJson / generateVolumeOutline /
 * extractKnowledgeConstraints）共用。
 *
 * 同时存在已有的 `ChatJsonHelpers`（getInt/getString/firstNonEmpty 等防御性 Gson getter），
 * 那个是更底层；这里是更上层的文本提取/规整工具。
 */
object ChatTextHelpers {

    fun tryParseObject(text: String?): JsonObject? {
        if (text == null || text.trim().isEmpty()) return null
        return try {
            JsonParser().parse(text).asJsonObject
        } catch (ignored: Exception) {
            null
        }
    }

    fun looksLikeTruncatedJson(text: String?): Boolean {
        if (text == null || text.isEmpty()) return false
        val first = text.indexOf('{')
        if (first < 0) return false
        var objDepth = 0
        var arrDepth = 0
        var inString = false
        var escaped = false
        for (i in first until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> objDepth++
                '}' -> objDepth = Math.max(0, objDepth - 1)
                '[' -> arrDepth++
                ']' -> arrDepth = Math.max(0, arrDepth - 1)
            }
        }
        return inString || objDepth > 0 || arrDepth > 0
    }

    fun sanitizeJsonLikeText(text: String?): String {
        var out = text?.trim() ?: ""
        if (out.isEmpty()) return ""
        // Remove fenced code markers.
        out = out.replace(Regex("(?is)^```(?:json)?\\s*"), "")
        out = out.replace(Regex("(?is)\\s*```$"), "")
        // Normalize full-width punctuation often seen in CJK outputs.
        out = out.replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
            .replace('：', ':')
            .replace('，', ',')
        return out.trim()
    }

    fun repairJsonCandidate(candidate: String?): String {
        var out = sanitizeJsonLikeText(candidate)
        if (out.isEmpty()) return ""
        // Try converting single-quoted JSON-like text to valid double-quoted JSON.
        out = out.replace(Regex("(?<!\\\\)'"), "\"")
        // Remove trailing commas before closing braces/brackets.
        out = out.replace(Regex(",\\s*([}\\]])"), "$1")
        return out
    }

    fun repairTruncatedJsonObject(raw: String?): String {
        if (raw == null || raw.isEmpty()) return ""
        val start = raw.indexOf('{')
        if (start < 0) return ""
        val text = raw.substring(start)
        val out = StringBuilder(text)
        val closers = java.util.ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> closers.push('}')
                '[' -> closers.push(']')
                '}', ']' -> {
                    if (closers.isNotEmpty() && closers.peek() == c) closers.pop()
                    else return ""
                }
            }
        }
        if (inString) return ""
        while (closers.isNotEmpty()) out.append(closers.pop())
        val fixed = out.toString().replace(Regex(",\\s*([}\\]])"), "$1")
        return fixed
    }

    fun extractAssistantContent(body: ChatApi.ChatResponse): String {
        val choices = body.choices
        if (choices == null || choices.isEmpty()) return ""
        val first = choices[0] ?: return ""
        val message = first.message ?: return ""
        val content: JsonElement = message.content ?: return ""
        if (content.isJsonNull) return ""
        try {
            if (content.isJsonPrimitive) return content.asString
            if (content.isJsonArray) {
                val out = StringBuilder()
                val arr = content.asJsonArray
                for (one in arr) {
                    if (one == null || one.isJsonNull) continue
                    if (one.isJsonPrimitive) {
                        out.append(one.asString)
                        continue
                    }
                    if (!one.isJsonObject) continue
                    val obj = one.asJsonObject
                    val txt = firstNonEmpty(
                        getStringFlexible(obj, "text"),
                        getStringFlexible(obj, "content"),
                        getStringFlexible(obj, "value")
                    )
                    if (txt.isNotEmpty()) out.append(txt)
                }
                return out.toString()
            }
            if (content.isJsonObject) {
                val obj = content.asJsonObject
                return firstNonEmpty(
                    getStringFlexible(obj, "text"),
                    getStringFlexible(obj, "content"),
                    getStringFlexible(obj, "value")
                )
            }
        } catch (ignored: Exception) {}
        return ""
    }

    fun stripThinkTags(text: String?): String {
        if (text == null || text.isEmpty()) return ""
        return text.replace(Regex("(?is)<think>.*?</think>"), "").trim()
    }

    fun cleanTitleResult(raw: String?): String {
        var text = stripThinkTags(raw)
        text = text.replace("\r", "\n").trim()

        // Remove common verbose reasoning prefixes from uncensored/local models.
        text = text.replace(Regex("(?is)^\\s*(thinking\\s*process|reasoning|analysis|思考过程|分析过程)\\s*[:：].*$"), "")
        if (text.isEmpty()) return ""

        // Prefer first non-empty line that looks like a short Chinese title.
        val lines = text.split(Regex("\\n+"))
        var best = ""
        for (line in lines) {
            var one = line.trim()
            if (one.isEmpty()) continue
            one = one.replace(Regex("^[\\-\\*\\d\\.\\)\\(\\[\\]【】\\s]+"), "").trim()
            one = one.replace(Regex("[。！？，,.!?:：;；\"'\\u201C\\u201D\\u2018\\u2019（）()\\[\\]{}]"), "").trim()
            if (one.isEmpty()) continue
            if (one.matches(Regex(".*[\\u4e00-\\u9fa5].*")) && one.length >= 3 && one.length <= 12) {
                return one
            }
            if (best.isEmpty()) best = one
        }

        if (best.isNotEmpty()) {
            best = best.replace(Regex("[。！？，,.!?:：;；\"'\\u201C\\u201D\\u2018\\u2019（）()\\[\\]{}]"), "").trim()
            return best
        }
        return text.replace("\n", " ").replace(Regex("[。！？，,.!?:：;；\"'\\u201C\\u201D\\u2018\\u2019（）()\\[\\]{}]"), "").trim()
    }

    fun extractTitleFromJsonOrText(raw: String?): String {
        val text = raw?.trim() ?: ""
        if (text.isEmpty()) return ""
        try {
            val jsonSlice = extractJsonObjectSlice(text)
            if (jsonSlice.isNotEmpty()) {
                val obj = JsonParser().parse(jsonSlice).asJsonObject
                val title = firstNonEmpty(
                    getStringFlexible(obj, "title"),
                    getStringFlexible(obj, "name"),
                    getStringFlexible(obj, "result")
                )
                if (title.trim().isNotEmpty()) return title.trim()
            }
        } catch (ignored: Exception) {}
        return text
    }

    fun extractTextFieldFromJsonOrText(raw: String?, vararg preferredKeys: String): String {
        val text = raw?.trim() ?: ""
        if (text.isEmpty()) return ""
        try {
            val jsonSlice = extractJsonObjectSlice(text)
            if (jsonSlice.isNotEmpty()) {
                val obj = JsonParser().parse(jsonSlice).asJsonObject
                for (key in preferredKeys) {
                    val value = getStringFlexible(obj, key)
                    if (value.trim().isNotEmpty()) return value.trim()
                }
                val fallback = firstNonEmpty(
                    getStringFlexible(obj, "text"),
                    getStringFlexible(obj, "message"),
                    getStringFlexible(obj, "data")
                )
                if (fallback.trim().isNotEmpty()) return fallback.trim()
            }
        } catch (ignored: Exception) {}
        return text
    }

    fun previewForLog(text: String?, maxLen: Int): String {
        val v = text?.replace("\n", "\\n")?.trim() ?: ""
        if (v.length <= Math.max(32, maxLen)) return v
        return v.substring(0, Math.max(32, maxLen)) + "..."
    }

    fun extractJsonObjectSlice(text: String?): String {
        if (text == null || text.isEmpty()) return ""
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        return text.substring(start, end + 1)
    }

}
