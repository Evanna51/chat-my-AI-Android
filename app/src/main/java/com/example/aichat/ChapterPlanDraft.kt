package com.example.aichat

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 章节计划生成的输入上下文。由 SessionOutlineActivity 收集大纲条目后传给
 * [ChatService.generateChapterPlanJson]，模型基于完整大纲为指定章节作计划。
 */
data class ChapterPlanContext(
    val targetTitle: String,
    /** true=覆盖已有章节计划；false=新建续写章节。 */
    val isExisting: Boolean,
    /** 目标章节当前的大纲文本（仅 isExisting=true 时有意义，可为空）。 */
    val existingContent: String,
    /** 全部章节（按 outline 顺序）。若 isExisting，目标章节包含在内；否则为现有全部章节。 */
    val allChapters: List<SessionOutlineItem>,
    val characters: List<SessionOutlineItem>,
    val worlds: List<SessionOutlineItem>,
    val knowledgeConstraints: List<SessionOutlineItem>,
    val materials: List<SessionOutlineItem>,
    /** 最近对话节选（已截断），可为空。 */
    val recentDialogue: String,
    /** 用户对本章的额外指示，可为空。 */
    val userHint: String,
    /** 期望篇幅（字数），从输入框透传给模型作为 targetLength 默认值。 */
    val targetLength: String,
    /** 大纲提示词（文风/风格指导），从助手或对话设置解析，可为空。 */
    val outlinePrompt: String = "",
)

class CharacterDrive {
    var name: String = ""
    var goal: String = ""
    var misbelief: String = ""
    var emotion: String = ""
}

/**
 * 章节计划结构。一份计划描述某个具体章节的目标、起止状态、人物驱动、知情边界、事件链等。
 */
class ChapterPlanDraft {
    var chapterGoal: String = ""
    var startState: String = ""
    var endState: String = ""
    var characterDrives: MutableList<CharacterDrive> = ArrayList()
    var knowledgeBoundary: List<String> = ArrayList()
    var eventChain: List<String> = ArrayList()
    var foreshadow: List<String> = ArrayList()
    var payoff: List<String> = ArrayList()
    var forbidden: List<String> = ArrayList()
    var styleGuide: String = ""
    var targetLength: String = ""

    fun toJson(): JsonObject {
        val out = JsonObject()
        out.addProperty("chapterGoal", chapterGoal)
        out.addProperty("startState", startState)
        out.addProperty("endState", endState)
        val drives = JsonArray()
        for (one in characterDrives) {
            val item = JsonObject()
            item.addProperty("name", one.name)
            item.addProperty("goal", one.goal)
            item.addProperty("misbelief", one.misbelief)
            item.addProperty("emotion", one.emotion)
            drives.add(item)
        }
        out.add("characterDrives", drives)
        out.add("knowledgeBoundary", toJsonArray(knowledgeBoundary))
        out.add("eventChain", toJsonArray(eventChain))
        out.add("foreshadow", toJsonArray(foreshadow))
        out.add("payoff", toJsonArray(payoff))
        out.add("forbidden", toJsonArray(forbidden))
        out.addProperty("styleGuide", styleGuide)
        val length = targetLength.trim()
        if (length.isNotEmpty()) out.addProperty("targetLength", length)
        return out
    }

    fun hasAnyContent(): Boolean {
        if (chapterGoal.trim().isNotEmpty()) return true
        if (startState.trim().isNotEmpty()) return true
        if (endState.trim().isNotEmpty()) return true
        if (styleGuide.trim().isNotEmpty()) return true
        if (targetLength.trim().isNotEmpty()) return true
        if (characterDrives.isNotEmpty()) return true
        if (knowledgeBoundary.isNotEmpty()) return true
        if (eventChain.isNotEmpty()) return true
        if (foreshadow.isNotEmpty()) return true
        if (payoff.isNotEmpty()) return true
        return forbidden.isNotEmpty()
    }

    /** 输出大纲条目用的可读文本（保存到 outline 时使用） */
    fun toOutlineText(): String {
        val sb = StringBuilder()
        if (chapterGoal.trim().isNotEmpty()) sb.append("【目标】\n").append(chapterGoal.trim()).append("\n\n")
        if (startState.trim().isNotEmpty()) sb.append("【起始状态】\n").append(startState.trim()).append("\n\n")
        if (endState.trim().isNotEmpty()) sb.append("【结束状态】\n").append(endState.trim()).append("\n\n")
        if (characterDrives.isNotEmpty()) {
            sb.append("【人物驱动】\n")
            for (one in characterDrives) {
                if (one.name.isEmpty() && one.goal.isEmpty()
                    && one.misbelief.isEmpty() && one.emotion.isEmpty()) continue
                sb.append("- ")
                if (one.name.isNotEmpty()) sb.append(one.name).append("：")
                val parts = mutableListOf<String>()
                if (one.goal.isNotEmpty()) parts.add("目标=" + one.goal)
                if (one.misbelief.isNotEmpty()) parts.add("误判=" + one.misbelief)
                if (one.emotion.isNotEmpty()) parts.add("情绪=" + one.emotion)
                sb.append(parts.joinToString("，")).append("\n")
            }
            sb.append("\n")
        }
        if (knowledgeBoundary.isNotEmpty()) {
            sb.append("【知情边界】\n")
            for (one in knowledgeBoundary) if (one.trim().isNotEmpty()) sb.append("- ").append(one.trim()).append("\n")
            sb.append("\n")
        }
        if (eventChain.isNotEmpty()) {
            sb.append("【事件链】\n")
            for ((i, one) in eventChain.withIndex()) {
                if (one.trim().isEmpty()) continue
                sb.append(i + 1).append(". ").append(one.trim()).append("\n")
            }
            sb.append("\n")
        }
        if (foreshadow.isNotEmpty()) {
            sb.append("【伏笔】\n")
            for (one in foreshadow) if (one.trim().isNotEmpty()) sb.append("- ").append(one.trim()).append("\n")
            sb.append("\n")
        }
        if (payoff.isNotEmpty()) {
            sb.append("【回收】\n")
            for (one in payoff) if (one.trim().isNotEmpty()) sb.append("- ").append(one.trim()).append("\n")
            sb.append("\n")
        }
        if (forbidden.isNotEmpty()) {
            sb.append("【禁写】\n")
            for (one in forbidden) if (one.trim().isNotEmpty()) sb.append("- ").append(one.trim()).append("\n")
            sb.append("\n")
        }
        if (styleGuide.trim().isNotEmpty()) sb.append("【文风与节奏】\n").append(styleGuide.trim()).append("\n\n")
        if (targetLength.trim().isNotEmpty()) sb.append("【目标篇幅】").append(targetLength.trim())
        return sb.toString().trim()
    }

    fun characterDrivesToMultiline(): String {
        if (characterDrives.isEmpty()) return ""
        val sb = StringBuilder()
        for (one in characterDrives) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(one.name).append("|").append(one.goal)
                .append("|").append(one.misbelief).append("|").append(one.emotion)
        }
        return sb.toString()
    }

    companion object {
        fun fromJson(obj: JsonObject?): ChapterPlanDraft {
            val out = ChapterPlanDraft()
            if (obj == null) return out
            out.chapterGoal = getString(obj, "chapterGoal")
            out.startState = getString(obj, "startState")
            out.endState = getString(obj, "endState")
            out.characterDrives = parseCharacterDrives(obj.get("characterDrives"))
            out.knowledgeBoundary = parseStringArray(obj.get("knowledgeBoundary"))
            out.eventChain = parseStringArray(obj.get("eventChain"))
            out.foreshadow = parseStringArray(obj.get("foreshadow"))
            out.payoff = parseStringArray(obj.get("payoff"))
            out.forbidden = parseStringArray(obj.get("forbidden"))
            out.styleGuide = getString(obj, "styleGuide")
            out.targetLength = getString(obj, "targetLength")
            return out
        }

        fun parseCharacterDrives(multiline: String?): MutableList<CharacterDrive> {
            val out = ArrayList<CharacterDrive>()
            if (multiline.isNullOrEmpty()) return out
            val lines = multiline.split(Regex("\\r?\\n"))
            for (line in lines) {
                if (line.trim().isEmpty()) continue
                val parts = line.split("|", limit = -1)
                val drive = CharacterDrive()
                drive.name = if (parts.isNotEmpty()) parts[0].trim() else ""
                drive.goal = if (parts.size > 1) parts[1].trim() else ""
                drive.misbelief = if (parts.size > 2) parts[2].trim() else ""
                drive.emotion = if (parts.size > 3) parts[3].trim() else ""
                out.add(drive)
            }
            return out
        }

        fun parseCharacterDrives(element: JsonElement?): MutableList<CharacterDrive> {
            val out = ArrayList<CharacterDrive>()
            if (element == null || element.isJsonNull || !element.isJsonArray) return out
            val arr = element.asJsonArray
            for (i in 0 until arr.size()) {
                val one = arr[i]
                if (one == null || one.isJsonNull) continue
                val drive = CharacterDrive()
                if (one.isJsonObject) {
                    val obj = one.asJsonObject
                    drive.name = getString(obj, "name")
                    drive.goal = getString(obj, "goal")
                    drive.misbelief = getString(obj, "misbelief")
                    drive.emotion = getString(obj, "emotion")
                } else {
                    drive.goal = if (one.isJsonPrimitive) one.asString else one.toString()
                }
                out.add(drive)
            }
            return out
        }

        private fun parseStringArray(element: JsonElement?): List<String> {
            val out = ArrayList<String>()
            if (element == null || element.isJsonNull || !element.isJsonArray) return out
            val arr = element.asJsonArray
            for (i in 0 until arr.size()) {
                val one = arr[i]
                if (one == null || one.isJsonNull) continue
                val text = if (one.isJsonPrimitive) one.asString else one.toString()
                if (text.trim().isNotEmpty()) out.add(text.trim())
            }
            return out
        }

        private fun toJsonArray(source: List<String>?): JsonArray {
            val out = JsonArray()
            if (source == null) return out
            for (one in source) {
                if (one.trim().isEmpty()) continue
                out.add(one.trim())
            }
            return out
        }

        private fun getString(obj: JsonObject?, key: String): String {
            if (obj == null || !obj.has(key)) return ""
            return try {
                val e = obj.get(key)
                if (e == null || e.isJsonNull) ""
                else if (e.isJsonPrimitive) e.asString
                else e.toString()
            } catch (ignored: Exception) {
                ""
            }
        }
    }
}
