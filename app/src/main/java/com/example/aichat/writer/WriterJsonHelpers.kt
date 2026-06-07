package com.example.aichat.writer

import com.example.aichat.chat.ChatJsonHelpers.firstNonEmpty
import com.example.aichat.chat.ChatJsonHelpers.getStringFlexible
import com.example.aichat.chat.ChatTextHelpers
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Writer 模式（作家/小说）专属的 JSON helper。
 * 用途：chapter plan / volume outline / knowledge constraints 等
 * 生成路径里的容错解析、规范化、关键字 fallback 等。
 *
 * 与 [ChatTextHelpers] 的分工：
 *   - ChatTextHelpers：所有 ChatService 入口都会用到的通用文本/JSON 工具
 *     （`stripThinkTags`, `extractAssistantContent`, `tryParseObject` 等）
 *   - WriterJsonHelpers：仅 writer-only 生成路径使用的工具
 */
object WriterJsonHelpers {

    fun parseFirstJsonObject(raw: String?): JsonObject? {
        val text = ChatTextHelpers.sanitizeJsonLikeText(ChatTextHelpers.stripThinkTags(raw))
        if (text.isEmpty()) return null
        // 1) Full parse first: parse the whole payload as a JSON object.
        val direct = ChatTextHelpers.tryParseObject(text)
        if (direct != null) return direct

        // 2) Full-slice parse: from first '{' to last '}' as one complete object.
        val fullSlice = ChatTextHelpers.extractJsonObjectSlice(text)
        val fullObj = ChatTextHelpers.tryParseObject(fullSlice)
        if (fullObj != null) return fullObj

        // 3) Only if likely truncated/non-normal ending, run fallback extraction.
        if (ChatTextHelpers.looksLikeTruncatedJson(text)) {
            val repaired = ChatTextHelpers.repairTruncatedJsonObject(text)
            val repairedObj = ChatTextHelpers.tryParseObject(repaired)
            if (repairedObj != null) return repairedObj
            val keywordObj = extractChapterPlanByKeywords(text)
            if (keywordObj != null) return keywordObj
        }
        return null
    }

    fun extractChapterPlanByKeywords(text: String?): JsonObject? {
        if (text == null || text.isEmpty()) return null
        val out = JsonObject()

        putIfNotEmpty(out, "chapterGoal", extractStringByKeys(text,
            "chapterGoal", "chapter_goal", "goal", "章节目标", "本章目标", "目标"))
        putIfNotEmpty(out, "startState", extractStringByKeys(text,
            "startState", "start_state", "起始状态", "开场状态", "开局状态"))
        putIfNotEmpty(out, "endState", extractStringByKeys(text,
            "endState", "end_state", "结束状态", "结尾状态", "收束状态"))
        putIfNotEmpty(out, "styleGuide", extractStringByKeys(text,
            "styleGuide", "style_guide", "style", "writingStyle", "文风", "文风与节奏"))

        putArrayIfNotEmpty(out, "knowledgeBoundary", extractArrayByKeys(text,
            "knowledgeBoundary", "knowledge_boundary", "knowledge", "知情边界", "知情约束"))
        putArrayIfNotEmpty(out, "eventChain", extractArrayByKeys(text,
            "eventChain", "event_chain", "events", "事件链", "关键事件"))
        putArrayIfNotEmpty(out, "foreshadow", extractArrayByKeys(text,
            "foreshadow", "foreshadows", "伏笔"))
        putArrayIfNotEmpty(out, "payoff", extractArrayByKeys(text,
            "payoff", "payoffs", "回收"))
        putArrayIfNotEmpty(out, "forbidden", extractArrayByKeys(text,
            "forbidden", "forbiddenList", "禁写清单", "禁写", "禁忌"))
        putCharacterDrivesIfNotEmpty(out, extractArrayByKeys(text,
            "characterDrives", "character_drives", "characters", "角色驱动", "角色动机"))

        return if (out.entrySet().isEmpty()) null else out
    }

    fun putIfNotEmpty(obj: JsonObject?, key: String?, value: String?) {
        if (obj == null || key == null) return
        if (value == null || value.trim().isEmpty()) return
        obj.addProperty(key, value.trim())
    }

    fun putArrayIfNotEmpty(obj: JsonObject?, key: String?, values: List<String>?) {
        if (obj == null || key == null || values == null || values.isEmpty()) return
        val arr = JsonArray()
        for (v in values) {
            if (v == null || v.trim().isEmpty()) continue
            arr.add(v.trim())
        }
        if (arr.size() > 0) obj.add(key, arr)
    }

    fun putCharacterDrivesIfNotEmpty(obj: JsonObject?, drives: List<String>?) {
        if (obj == null || drives == null || drives.isEmpty()) return
        val arr = JsonArray()
        for (v in drives) {
            if (v == null || v.trim().isEmpty()) continue
            val one = JsonObject()
            one.addProperty("name", "")
            one.addProperty("goal", v.trim())
            one.addProperty("misbelief", "")
            one.addProperty("emotion", "")
            arr.add(one)
        }
        if (arr.size() > 0) obj.add("characterDrives", arr)
    }

    fun extractStringByKeys(text: String?, vararg keys: String): String {
        if (text == null) return ""
        for (key in keys) {
            if (key.isEmpty()) continue
            val p = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.DOTALL
            )
            val m = p.matcher(text)
            if (m.find()) {
                val v = m.group(1)
                if (v != null && v.trim().isNotEmpty()) return v.trim()
            }
        }
        return ""
    }

    fun extractArrayByKeys(text: String?, vararg keys: String): List<String> {
        val out = ArrayList<String>()
        if (text == null) return out
        for (key in keys) {
            if (key.isEmpty()) continue
            val p = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]",
                java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.DOTALL
            )
            val m = p.matcher(text)
            if (!m.find()) continue
            val body = m.group(1)
            if (body == null || body.trim().isEmpty()) continue
            val item = java.util.regex.Pattern
                .compile("\"([^\"]*)\"")
                .matcher(body)
            while (item.find()) {
                val v = item.group(1)
                if (v != null && v.trim().isNotEmpty()) out.add(v.trim())
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    fun normalizeChapterPlanJson(source: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("chapterGoal", pickString(source, "chapterGoal", "chapter_goal", "goal", "章节目标", "本章目标", "目标"))
        out.addProperty("startState", pickString(source, "startState", "start_state", "起始状态", "开场状态", "开局状态"))
        out.addProperty("endState", pickString(source, "endState", "end_state", "结束状态", "结尾状态", "收束状态"))
        out.add("characterDrives", normalizeCharacterDrives(pickElement(source,
            "characterDrives", "character_drives", "characters", "角色驱动", "角色动机")))
        out.add("knowledgeBoundary", normalizeStringArray(pickElement(source,
            "knowledgeBoundary", "knowledge_boundary", "knowledge", "知情边界", "知情约束")))
        out.add("eventChain", normalizeStringArray(pickElement(source,
            "eventChain", "event_chain", "events", "事件链", "关键事件")))
        out.add("foreshadow", normalizeStringArray(pickElement(source,
            "foreshadow", "foreshadows", "伏笔")))
        out.add("payoff", normalizeStringArray(pickElement(source,
            "payoff", "payoffs", "回收")))
        out.add("forbidden", normalizeStringArray(pickElement(source,
            "forbidden", "forbiddenList", "禁写清单", "禁写", "禁忌")))
        out.addProperty("styleGuide", pickString(source, "styleGuide", "style_guide", "style", "writingStyle", "文风", "文风与节奏"))
        // Keep target length blank so user can decide it manually in dialog.
        out.addProperty("targetLength", "")
        return out
    }

    fun pickElement(source: JsonObject?, vararg keys: String): JsonElement? {
        if (source == null) return null
        for (key in keys) {
            if (key.isEmpty()) continue
            val e = source.get(key)
            if (e != null && !e.isJsonNull) return e
        }
        return null
    }

    fun pickString(source: JsonObject?, vararg keys: String): String {
        if (source == null) return ""
        for (key in keys) {
            val v = getStringFlexible(source, key)
            if (v.trim().isNotEmpty()) return v.trim()
        }
        return ""
    }

    fun countNonEmptyPlanFields(plan: JsonObject?): Int {
        if (plan == null) return 0
        var count = 0
        if (getStringFlexible(plan, "chapterGoal").trim().isNotEmpty()) count++
        if (getStringFlexible(plan, "startState").trim().isNotEmpty()) count++
        if (getStringFlexible(plan, "endState").trim().isNotEmpty()) count++
        if (getStringFlexible(plan, "styleGuide").trim().isNotEmpty()) count++
        if (plan.has("characterDrives") && plan.get("characterDrives").isJsonArray
            && plan.getAsJsonArray("characterDrives").size() > 0) count++
        if (plan.has("knowledgeBoundary") && plan.get("knowledgeBoundary").isJsonArray
            && plan.getAsJsonArray("knowledgeBoundary").size() > 0) count++
        if (plan.has("eventChain") && plan.get("eventChain").isJsonArray
            && plan.getAsJsonArray("eventChain").size() > 0) count++
        if (plan.has("foreshadow") && plan.get("foreshadow").isJsonArray
            && plan.getAsJsonArray("foreshadow").size() > 0) count++
        if (plan.has("payoff") && plan.get("payoff").isJsonArray
            && plan.getAsJsonArray("payoff").size() > 0) count++
        if (plan.has("forbidden") && plan.get("forbidden").isJsonArray
            && plan.getAsJsonArray("forbidden").size() > 0) count++
        return count
    }

    fun normalizeStringArray(element: JsonElement?): JsonArray {
        val out = JsonArray()
        if (element == null || element.isJsonNull || !element.isJsonArray) return out
        val arr = element.asJsonArray
        for (i in 0 until arr.size()) {
            val one = arr.get(i)
            if (one == null || one.isJsonNull) continue
            if (one.isJsonPrimitive) out.add(one.asString)
            else if (one.isJsonObject) {
                val text = firstNonEmpty(
                    getStringFlexible(one.asJsonObject, "text"),
                    getStringFlexible(one.asJsonObject, "value"),
                    one.toString()
                )
                if (text.trim().isNotEmpty()) out.add(text.trim())
            } else {
                out.add(one.toString())
            }
        }
        return out
    }

    fun normalizeCharacterDrives(element: JsonElement?): JsonArray {
        val out = JsonArray()
        if (element == null || element.isJsonNull || !element.isJsonArray) return out
        val arr = element.asJsonArray
        for (i in 0 until arr.size()) {
            val one = arr.get(i)
            if (one == null || one.isJsonNull) continue
            val item = JsonObject()
            if (one.isJsonObject) {
                val src = one.asJsonObject
                item.addProperty("name", getStringFlexible(src, "name"))
                item.addProperty("goal", getStringFlexible(src, "goal"))
                item.addProperty("misbelief", getStringFlexible(src, "misbelief"))
                item.addProperty("emotion", getStringFlexible(src, "emotion"))
            } else {
                val text = if (one.isJsonPrimitive) one.asString else one.toString()
                item.addProperty("name", "")
                item.addProperty("goal", text)
                item.addProperty("misbelief", "")
                item.addProperty("emotion", "")
            }
            out.add(item)
        }
        return out
    }
}
