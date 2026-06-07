package com.example.aichat.story

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * OpenAI-style tool schemas for the Story Tools. 注入到 chat 请求的 `tools` 数组,
 * 由 LLM 调用; 调用结果由 [StoryToolHandler] 在本地执行 (操作 SessionOutlineStore)。
 *
 * 设计哲学 (参考 inkos agent-tools):
 *  - 每个 tool 单一职责, 不做大 batch
 *  - 参数尽量少, 避免 LLM 因参数表过长不调用
 *  - 失败用 `{ok:false, error, message}` 反馈, LLM 自己修
 */
object StoryToolSchemas {

    val ALL: JsonArray by lazy {
        JsonArray().apply {
            add(LIST_OUTLINE)
            add(READ_OUTLINE_ITEM)
            add(ADD_OUTLINE_ITEM)
            add(UPDATE_OUTLINE_ITEM)
            add(DELETE_OUTLINE_ITEM)
            add(RENAME_ROLE)
            add(BUMP_FORESHADOW)
            add(UPDATE_SUBPLOT)
            add(UPDATE_EMOTION)
            add(APPEND_STATUS)
            add(PATCH_CHAPTER)
        }
    }

    private val VALID_TYPES = listOf(
        "chapter", "volume", "world", "knowledge",
        "roles", "foreshadow", "status", "relation", "subplot", "emotion", "rules",
    )

    val LIST_OUTLINE: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_LIST,
            "List outline items in the current writing session. " +
                "Use to discover what already exists before adding new things.",
            requiredFields = emptyList(),
        ) {
            add("type", enumProp(VALID_TYPES + listOf(""),
                "Filter by type, or empty to list all (e.g. 'roles' for characters)"))
        }
    }

    val READ_OUTLINE_ITEM: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_READ,
            "Read full content + metaJson of one outline item by id.",
            requiredFields = listOf("id"),
        ) {
            add("id", strProp("Item id (from list_outline)"))
        }
    }

    val ADD_OUTLINE_ITEM: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_ADD,
            "Create a new outline item. " +
                "Use this to register a character (type=roles), foreshadow, world setting, etc. " +
                "metaJson must match the type's schema (see project docs).",
            requiredFields = listOf("type"),
        ) {
            add("type", enumProp(VALID_TYPES, "Item type"))
            add("title", strProp("Title or name (e.g. role name)"))
            add("content", strProp("Free-text content; for chapters this is the chapter outline"))
            add("metaJson", strProp(
                "JSON string with type-specific structured fields. Example for roles: " +
                    "{\"tier\":\"major\",\"tags\":[\"冷静\"],\"personality\":\"...\",\"background\":\"...\"}. " +
                    "Empty string = no metaJson (chapter/world/knowledge typically don't need it)."))
        }
    }

    val UPDATE_OUTLINE_ITEM: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_UPDATE,
            "Partially update an existing outline item. Only the supplied fields change. " +
                "For metaJson, pass a JSON patch — fields in the patch overwrite, others stay.",
            requiredFields = listOf("id"),
        ) {
            add("id", strProp("Item id"))
            add("title", strProp("New title (omit to keep)"))
            add("content", strProp("New content (omit to keep)"))
            add("metaJson", strProp("Patch JSON (e.g. '{\"tier\":\"minor\"}') (omit to keep)"))
            add("selected", boolProp("Whether item participates in prompt (omit to keep)"))
        }
    }

    val DELETE_OUTLINE_ITEM: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_DELETE,
            "Delete an outline item by id. Irreversible; only delete when the entity " +
                "is genuinely retired / wrong / superseded.",
            requiredFields = listOf("id"),
        ) {
            add("id", strProp("Item id to delete"))
            add("reason", strProp("Audit log reason (optional)"))
        }
    }

    val RENAME_ROLE: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_RENAME_ROLE,
            "Rename a role globally: updates the role's title AND replaces every literal " +
                "occurrence of oldName in any outline item's content. " +
                "Use when the user requests a renaming.",
            requiredFields = listOf("oldName", "newName"),
        ) {
            add("oldName", strProp("Existing role name (exact)"))
            add("newName", strProp("New role name"))
        }
    }

    val BUMP_FORESHADOW: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_BUMP_FORESHADOW,
            "Advance a foreshadow's state: planted → developing → paid_off. " +
                "Use as you write each chapter and bury / develop / cash in foreshadows.",
            requiredFields = listOf("id", "state"),
        ) {
            add("id", strProp("Foreshadow item id"))
            add("state", enumProp(listOf("planted", "developing", "paid_off"), "Target state"))
            add("chapter", strProp("Chapter where this transition happened (e.g. '第3章')"))
        }
    }

    val UPDATE_SUBPLOT: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_UPDATE_SUBPLOT,
            "Push a subplot's progress and optionally add a milestone. " +
                "Call after a chapter that moves a subplot forward.",
            requiredFields = listOf("id"),
        ) {
            add("id", strProp("Subplot item id"))
            add("progress", intProp("New progress 0-100 (omit to keep)", 0, 100))
            add("addMilestone", JsonObject().apply {
                addProperty("type", "object")
                addProperty("description", "Optional milestone to append")
                add("properties", JsonObject().apply {
                    add("chapter", strProp("Chapter label"))
                    add("desc", strProp("What happened"))
                    add("done", boolProp("Already happened in story"))
                })
            })
        }
    }

    val UPDATE_EMOTION: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_UPDATE_EMOTION,
            "Advance an emotion line: change stage and/or progress.",
            requiredFields = listOf("id"),
        ) {
            add("id", strProp("Emotion item id"))
            add("stage", enumProp(
                listOf("陌生", "试探", "靠近", "亲密", "破裂", "和解", "分别"),
                "New stage (omit to keep)"))
            add("progress", intProp("New progress 0-100", 0, 100))
        }
    }

    val APPEND_STATUS: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_APPEND_STATUS,
            "Append a change to a status card's history (e.g. health, mood, power level). " +
                "History is stored on the wi-server; current value updates locally.",
            requiredFields = listOf("statusCardId", "change"),
        ) {
            add("statusCardId", strProp("Status card item id"))
            add("chapter", strProp("Chapter label (e.g. '第5章')"))
            add("change", strProp("New current value or description of change"))
        }
    }

    val PATCH_CHAPTER: JsonObject by lazy {
        tool(StoryToolHandler.TOOL_PATCH_CHAPTER,
            "Replace a unique substring in a chapter's content. Fails if targetText is " +
                "ambiguous (appears multiple times) — make it more specific.",
            requiredFields = listOf("chapterId", "targetText", "replacementText"),
        ) {
            add("chapterId", strProp("Chapter item id"))
            add("targetText", strProp("Exact substring to replace (must be unique)"))
            add("replacementText", strProp("New text"))
        }
    }

    // ─────────────── helpers ───────────────

    private fun tool(
        name: String,
        description: String,
        requiredFields: List<String>,
        propsBuilder: JsonObject.() -> Unit,
    ): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                if (requiredFields.isNotEmpty()) {
                    add("required", JsonArray().apply { requiredFields.forEach { add(it) } })
                }
                add("properties", JsonObject().apply { propsBuilder() })
            })
        })
    }

    private fun strProp(description: String?): JsonObject = JsonObject().apply {
        addProperty("type", "string")
        if (description != null) addProperty("description", description)
    }

    private fun intProp(description: String, min: Int? = null, max: Int? = null): JsonObject =
        JsonObject().apply {
            addProperty("type", "integer")
            if (min != null) addProperty("minimum", min)
            if (max != null) addProperty("maximum", max)
            addProperty("description", description)
        }

    private fun boolProp(description: String): JsonObject = JsonObject().apply {
        addProperty("type", "boolean")
        addProperty("description", description)
    }

    private fun enumProp(values: List<String>, description: String): JsonObject =
        JsonObject().apply {
            addProperty("type", "string")
            add("enum", JsonArray().apply { values.forEach { add(it) } })
            addProperty("description", description)
        }
}
