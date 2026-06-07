package com.example.aichat.story

import android.content.Context
import android.util.Log
import com.example.aichat.SessionOutlineItem
import com.example.aichat.SessionOutlineStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 本地 Story Tools 执行器 — 对应 inkos 的 agent-tools, 但操作对象是本地
 * SessionOutlineStore 而不是文件系统。模型在 writer 模式下可以调用这些 tool
 * 增改 outline。
 *
 * 工具集:
 *   list_outline          — 列出全部或按 type 过滤的条目 (id + title + 摘要)
 *   read_outline_item     — 读单条完整内容 + metaJson
 *   add_outline_item      — 新建
 *   update_outline_item   — 局部 patch
 *   delete_outline_item   — 删除
 *   rename_role           — 改 roles 条目 title + 全 outline 字面替换 (仿 inkos renameEntity)
 *   bump_foreshadow       — planted → developing → paid_off
 *   update_subplot_progress — 推进 + 可选 milestone
 *   update_emotion_stage  — 推进感情线
 *   append_status_history — append 状态卡历史 (走 wi-server, 当前 stub)
 *   patch_chapter_text    — 章节内文本精准替换 (仿 inkos patchChapterText)
 */
object StoryToolHandler {

    private const val TAG = "StoryToolHandler"

    // ─────────────── Tool name constants (Kotlin 初始化按声明顺序, 必须早于 ALL_TOOL_NAMES) ───────────────

    const val TOOL_LIST = "list_outline"
    const val TOOL_READ = "read_outline_item"
    const val TOOL_ADD = "add_outline_item"
    const val TOOL_UPDATE = "update_outline_item"
    const val TOOL_DELETE = "delete_outline_item"
    const val TOOL_RENAME_ROLE = "rename_role"
    const val TOOL_BUMP_FORESHADOW = "bump_foreshadow"
    const val TOOL_UPDATE_SUBPLOT = "update_subplot_progress"
    const val TOOL_UPDATE_EMOTION = "update_emotion_stage"
    const val TOOL_APPEND_STATUS = "append_status_history"
    const val TOOL_PATCH_CHAPTER = "patch_chapter_text"

    val ALL_TOOL_NAMES: List<String> = listOf(
        TOOL_LIST, TOOL_READ, TOOL_ADD, TOOL_UPDATE, TOOL_DELETE,
        TOOL_RENAME_ROLE, TOOL_BUMP_FORESHADOW, TOOL_UPDATE_SUBPLOT,
        TOOL_UPDATE_EMOTION, TOOL_APPEND_STATUS, TOOL_PATCH_CHAPTER,
    )

    fun isStoryTool(name: String?): Boolean = name in ALL_TOOL_NAMES

    /**
     * 入口: 路由到具体 handler。失败统一返回 `{ok:false, error, message}` JSON 字符串,
     * 让 LLM 看到错误并自行修复 (绝不抛异常)。
     */
    fun invoke(context: Context, sessionId: String, toolName: String, argumentsJson: String): String {
        return try {
            val args = parseArgs(argumentsJson) ?: return errorJson("bad_arguments", "invalid JSON")
            val store = SessionOutlineStore(context.applicationContext)
            when (toolName) {
                TOOL_LIST -> listOutline(store, sessionId, args)
                TOOL_READ -> readOutlineItem(store, sessionId, args)
                TOOL_ADD -> addOutlineItem(store, sessionId, args)
                TOOL_UPDATE -> updateOutlineItem(store, sessionId, args)
                TOOL_DELETE -> deleteOutlineItem(store, sessionId, args)
                TOOL_RENAME_ROLE -> renameRole(store, sessionId, args)
                TOOL_BUMP_FORESHADOW -> bumpForeshadow(store, sessionId, args)
                TOOL_UPDATE_SUBPLOT -> updateSubplot(store, sessionId, args)
                TOOL_UPDATE_EMOTION -> updateEmotion(store, sessionId, args)
                TOOL_APPEND_STATUS -> appendStatusHistory(context, store, sessionId, args)
                TOOL_PATCH_CHAPTER -> patchChapterText(store, sessionId, args)
                else -> errorJson("unknown_tool", "story tool '$toolName' not registered")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "story tool '$toolName' failed", t)
            errorJson("tool_failure", t.message ?: t.javaClass.simpleName)
        }
    }

    // ─────────────── per-tool ───────────────

    private fun listOutline(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val typeFilter = args.optString("type", "")
        val all = store.getAll(sessionId)
        val filtered = if (typeFilter.isNotBlank()) all.filter { it.type == typeFilter } else all
        val arr = JsonArray()
        for (it in filtered) {
            val o = JsonObject()
            o.addProperty("id", it.id)
            o.addProperty("type", it.type)
            o.addProperty("title", it.title)
            o.addProperty("summary", it.content.take(80))
            arr.add(o)
        }
        return okJson { add("items", arr); addProperty("count", arr.size()) }
    }

    private fun readOutlineItem(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val id = args.requireString("id") ?: return errorJson("bad_arguments", "missing 'id'")
        val item = store.findById(sessionId, id) ?: return errorJson("not_found", "no item with id=$id")
        return okJson {
            addProperty("id", item.id)
            addProperty("type", item.type)
            addProperty("title", item.title)
            addProperty("content", item.content)
            addProperty("metaJson", item.metaJson)
            addProperty("selected", item.selected)
        }
    }

    private fun addOutlineItem(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val type = args.requireString("type") ?: return errorJson("bad_arguments", "missing 'type'")
        if (!StoryTypes.isValid(type)) return errorJson("bad_arguments", "invalid type '$type'")
        val title = args.optString("title", "")
        val content = args.optString("content", "")
        val metaJson = args.optString("metaJson", "")
        val item = store.add(sessionId, type, title, content, metaJson)
        return okJson { addProperty("id", item.id); addProperty("type", item.type) }
    }

    private fun updateOutlineItem(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val id = args.requireString("id") ?: return errorJson("bad_arguments", "missing 'id'")
        val item = store.findById(sessionId, id) ?: return errorJson("not_found", "no item with id=$id")
        if (args.has("title") && !args.get("title").isJsonNull) item.title = args.get("title").asString
        if (args.has("content") && !args.get("content").isJsonNull) item.content = args.get("content").asString
        if (args.has("metaJson") && !args.get("metaJson").isJsonNull) {
            val patchOrFull = args.get("metaJson").asString
            // 如果是合法 JSON 且老 metaJson 也是,做 merge; 否则整段替换
            item.metaJson = if (looksLikeJson(patchOrFull) && looksLikeJson(item.metaJson)) {
                StoryMeta.patchMetaJson(item.metaJson, patchOrFull)
            } else patchOrFull
        }
        if (args.has("selected") && !args.get("selected").isJsonNull) item.selected = args.get("selected").asBoolean
        store.update(sessionId, item)
        return okJson { addProperty("id", item.id) }
    }

    private fun deleteOutlineItem(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val id = args.requireString("id") ?: return errorJson("bad_arguments", "missing 'id'")
        store.findById(sessionId, id) ?: return errorJson("not_found", "no item with id=$id")
        store.delete(sessionId, id)
        return okJson { addProperty("id", id); addProperty("deleted", true) }
    }

    private fun renameRole(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val oldName = args.requireString("oldName") ?: return errorJson("bad_arguments", "missing 'oldName'")
        val newName = args.requireString("newName") ?: return errorJson("bad_arguments", "missing 'newName'")
        if (oldName == newName) return errorJson("noop", "oldName == newName")
        val all = store.getAll(sessionId).toMutableList()
        var renamed = 0
        var replacedInContent = 0
        for (item in all) {
            if (item.type == StoryTypes.ROLES && item.title.trim() == oldName) {
                item.title = newName
                renamed++
            }
            // 全 outline 字面替换 content
            val before = item.content
            val after = before.replace(oldName, newName)
            if (after != before) {
                item.content = after
                replacedInContent++
            }
        }
        if (renamed == 0 && replacedInContent == 0) {
            return errorJson("not_found", "no role or text matched '$oldName'")
        }
        store.saveAll(sessionId, all)
        return okJson {
            addProperty("renamedRoleEntries", renamed)
            addProperty("replacedInContent", replacedInContent)
        }
    }

    private fun bumpForeshadow(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val id = args.requireString("id") ?: return errorJson("bad_arguments", "missing 'id'")
        val newState = args.requireString("state") ?: return errorJson("bad_arguments", "missing 'state'")
        if (newState !in setOf("planted", "developing", "paid_off")) {
            return errorJson("bad_arguments", "invalid state: $newState")
        }
        val item = store.findById(sessionId, id) ?: return errorJson("not_found", "no item with id=$id")
        if (item.type != StoryTypes.FORESHADOW) return errorJson("type_mismatch", "id=$id is type=${item.type}")
        val meta = StoryMeta.parseForeshadow(item.metaJson)
        val chapter = args.optString("chapter", "")
        val updated = meta.copy(
            state = newState,
            paidOffChapter = if (newState == "paid_off" && chapter.isNotBlank()) chapter else meta.paidOffChapter,
            plantedChapter = if (newState != "paid_off" && chapter.isNotBlank() && meta.plantedChapter.isBlank()) chapter else meta.plantedChapter,
        )
        item.metaJson = StoryMeta.toJson(updated)
        store.update(sessionId, item)
        return okJson { addProperty("id", id); addProperty("state", newState) }
    }

    private fun updateSubplot(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val id = args.requireString("id") ?: return errorJson("bad_arguments", "missing 'id'")
        val item = store.findById(sessionId, id) ?: return errorJson("not_found", "no item with id=$id")
        if (item.type != StoryTypes.SUBPLOT) return errorJson("type_mismatch", "id=$id is type=${item.type}")
        val meta = StoryMeta.parseSubplot(item.metaJson)
        val newProgress = if (args.has("progress") && !args.get("progress").isJsonNull)
            args.get("progress").asInt.coerceIn(0, 100) else meta.progress
        val newMilestones = meta.milestones.toMutableList()
        val addMs = args.optJsonObject("addMilestone")
        if (addMs != null) {
            newMilestones += StoryMeta.SubplotMilestone(
                chapter = addMs.optString("chapter", ""),
                desc = addMs.optString("desc", ""),
                done = addMs.optBool("done", false),
            )
        }
        item.metaJson = StoryMeta.toJson(meta.copy(progress = newProgress, milestones = newMilestones))
        store.update(sessionId, item)
        return okJson { addProperty("id", id); addProperty("progress", newProgress) }
    }

    private fun updateEmotion(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val id = args.requireString("id") ?: return errorJson("bad_arguments", "missing 'id'")
        val item = store.findById(sessionId, id) ?: return errorJson("not_found", "no item with id=$id")
        if (item.type != StoryTypes.EMOTION) return errorJson("type_mismatch", "id=$id is type=${item.type}")
        val meta = StoryMeta.parseEmotion(item.metaJson)
        val updated = meta.copy(
            stage = args.optString("stage", meta.stage),
            progress = args.optInt("progress", meta.progress).coerceIn(0, 100),
        )
        item.metaJson = StoryMeta.toJson(updated)
        store.update(sessionId, item)
        return okJson { addProperty("id", id); addProperty("stage", updated.stage) }
    }

    /**
     * S6 占位: history 应写到 wi-server `/api/story/status-history`。
     * 当前服务端未提供该 endpoint, 这里只更新 current 字段 + 记 log 留痕,
     * 等 wi-server 加 endpoint 后补 HTTP 调用。
     */
    private fun appendStatusHistory(
        context: Context, store: SessionOutlineStore, sessionId: String, args: JsonObject,
    ): String {
        val cardId = args.requireString("statusCardId") ?: return errorJson("bad_arguments", "missing 'statusCardId'")
        val chapter = args.optString("chapter", "")
        val change = args.requireString("change") ?: return errorJson("bad_arguments", "missing 'change'")
        val item = store.findById(sessionId, cardId) ?: return errorJson("not_found", "no item with id=$cardId")
        if (item.type != StoryTypes.STATUS) return errorJson("type_mismatch", "id=$cardId is type=${item.type}")

        // 本地: 同步 current
        val meta = StoryMeta.parseStatus(item.metaJson)
        item.metaJson = StoryMeta.toJson(meta.copy(current = change))
        store.update(sessionId, item)

        // TODO(wi-server): POST /api/story/status-history {sessionId, statusCardId, chapter, change}
        Log.i(TAG, "[stub] status-history append: sid=$sessionId card=$cardId ch=$chapter change=$change")

        return okJson {
            addProperty("id", cardId)
            addProperty("currentUpdated", true)
            addProperty("historyPersisted", false)
            addProperty("note", "wi-server endpoint pending; current updated locally")
        }
    }

    private fun patchChapterText(store: SessionOutlineStore, sessionId: String, args: JsonObject): String {
        val id = args.requireString("chapterId") ?: return errorJson("bad_arguments", "missing 'chapterId'")
        val target = args.requireString("targetText") ?: return errorJson("bad_arguments", "missing 'targetText'")
        val replacement = args.requireString("replacementText") ?: return errorJson("bad_arguments", "missing 'replacementText'")
        val item = store.findById(sessionId, id) ?: return errorJson("not_found", "no item with id=$id")
        if (item.type != StoryTypes.CHAPTER) return errorJson("type_mismatch", "id=$id is type=${item.type}")
        if (target !in item.content) return errorJson("not_found", "targetText not found in chapter content")
        val occurrences = item.content.split(target).size - 1
        if (occurrences > 1) return errorJson("ambiguous", "targetText appears $occurrences times; make it more specific")
        item.content = item.content.replace(target, replacement)
        store.update(sessionId, item)
        return okJson { addProperty("id", id); addProperty("occurrencesReplaced", 1) }
    }

    // ─────────────── helpers ───────────────

    private fun parseArgs(json: String): JsonObject? = try {
        JsonParser().parse(json).asJsonObject
    } catch (_: Throwable) { null }

    private fun JsonObject.requireString(key: String): String? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        val s = el.asString
        return s.ifBlank { null }
    }

    private fun JsonObject.optString(key: String, default: String): String {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return el.asString
    }

    private fun JsonObject.optInt(key: String, default: Int): Int {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return try { el.asInt } catch (_: Throwable) { default }
    }

    private fun JsonObject.optBool(key: String, default: Boolean): Boolean {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return try { el.asBoolean } catch (_: Throwable) { default }
    }

    private fun JsonObject.optJsonObject(key: String): JsonObject? {
        val el = get(key) ?: return null
        if (el.isJsonNull || !el.isJsonObject) return null
        return el.asJsonObject
    }

    private fun looksLikeJson(s: String): Boolean = s.trim().startsWith("{")

    private fun okJson(block: JsonObject.() -> Unit): String =
        JsonObject().apply { addProperty("ok", true); block() }.toString()

    private fun errorJson(code: String, message: String): String =
        JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", code)
            addProperty("message", message)
        }.toString()
}
