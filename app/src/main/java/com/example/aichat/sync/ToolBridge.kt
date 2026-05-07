package com.example.aichat.sync

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Bridges client-side tool definitions exposed to the LLM with their concrete
 * server-side handlers:
 *   - `search_memory`  → POST /api/tool/memory-recall
 *   - `correct_memory` → POST /api/tool/memory-correct
 *
 * The bridge is constructed per chat dispatch with the session's bound
 * assistantId/sessionId so the LLM stays agnostic of those — tool schemas
 * never expose `assistantId` to the model (cross-assistant boundary protection).
 */
class ToolBridge(
    private val assistantId: String,
    private val sessionId: String,
    private val baseUrl: String,
    private val apiKey: String,
) {

    fun isReady(): Boolean = assistantId.isNotEmpty() && baseUrl.isNotEmpty()

    /** OpenAI-style tool descriptors injected into the chat request. */
    fun toolsJson(): JsonArray = JsonArray().apply {
        add(SEARCH_MEMORY_TOOL_SCHEMA)
        add(CORRECT_MEMORY_TOOL_SCHEMA)
    }

    /**
     * Execute a tool call. Returns the tool message content (typically the raw
     * server JSON) or an error string. Never throws; failures are surfaced to
     * the LLM as the tool result so it can recover.
     */
    fun invoke(toolName: String, argumentsJson: String): String {
        return try {
            when (toolName) {
                TOOL_SEARCH_MEMORY -> invokeSearchMemory(argumentsJson)
                TOOL_CORRECT_MEMORY -> invokeCorrectMemory(argumentsJson)
                else -> errorJson("unknown_tool", "tool '$toolName' is not registered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "tool '$toolName' failed", e)
            errorJson("tool_failure", e.message ?: "unknown error")
        }
    }

    private fun invokeSearchMemory(argumentsJson: String): String {
        val args = parseArgs(argumentsJson) ?: return errorJson("bad_arguments", "invalid JSON")
        val queryEl = args.get("query")
        val query = if (queryEl == null || queryEl.isJsonNull) "" else queryEl.asString
        if (query.trim().isEmpty()) return errorJson("bad_arguments", "missing 'query'")
        return MemoryToolApi(baseUrl, apiKey).memoryRecall(assistantId, sessionId, args)
    }

    private fun invokeCorrectMemory(argumentsJson: String): String {
        val args = parseArgs(argumentsJson) ?: return errorJson("bad_arguments", "invalid JSON")
        val actionEl = args.get("action")
        val action = if (actionEl == null || actionEl.isJsonNull) "" else actionEl.asString
        if (action.trim().isEmpty()) return errorJson("bad_arguments", "missing 'action'")
        return MemoryToolApi(baseUrl, apiKey).memoryCorrect(assistantId, args)
    }

    private fun parseArgs(json: String): JsonObject? = try {
        JsonParser().parse(json).asJsonObject
    } catch (_: Exception) {
        null
    }

    private fun errorJson(code: String, message: String): String =
        JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", code)
            addProperty("message", message)
        }.toString()

    companion object {
        private const val TAG = "ToolBridge"
        const val TOOL_SEARCH_MEMORY = "search_memory"
        const val TOOL_CORRECT_MEMORY = "correct_memory"

        private val CATEGORY_VALUES = listOf(
            "chitchat", "personal_experience", "relationship_info", "knowledge",
            "goals_plans", "preferences", "decisions_reflections", "wellbeing", "ideas"
        )
        private val MEMORY_TYPE_VALUES = listOf(
            "user_turn", "assistant_turn", "life_event", "work_event",
            "tool_call", "tool_result", "system_event"
        )
        private val QUALITY_GRADES = listOf("A", "B", "C", "D", "E")
        private val SOURCE_VALUES = listOf("user", "character", "all")
        private val CORRECT_ACTIONS = listOf(
            "delete", "delete_batch", "update", "set_quality", "add_fact", "remove_fact"
        )

        /** OpenAI tool schema for memory-recall. `assistantId` is injected by the bridge. */
        val SEARCH_MEMORY_TOOL_SCHEMA: JsonObject by lazy {
            JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", TOOL_SEARCH_MEMORY)
                    addProperty(
                        "description",
                        "Search user/character memory. Use when user references past events, " +
                            "preferences, plans or relationships. " +
                            "query: refined topic words, not full user sentence.\n" +
                            "Time params: pass dateString only if user names a specific date " +
                            "(yesterday/3-13/etc, computed from user's words, NOT today). " +
                            "Pass withinDays only if user names a range (recent/last week). " +
                            "For attribute questions (likes/height/family) pass NO time param. " +
                            "When unsure, omit time params.\n" +
                            "If count=0 or no semantic match, tell user there is no record. Never fabricate."
                    )
                    add("parameters", JsonObject().apply {
                        addProperty("type", "object")
                        add("required", JsonArray().apply { add("query") })
                        add("properties", JsonObject().apply {
                            add("query", strProp("Refined topic words"))
                            add("topK", intProp("1-20, default 5", min = 1, max = 20))
                            add("source", enumProp(SOURCE_VALUES, "default user"))
                            add("category", enumProp(CATEGORY_VALUES, "Optional category filter"))
                            add("memoryType", enumProp(MEMORY_TYPE_VALUES, "Overrides source"))
                            add("minQuality", enumProp(QUALITY_GRADES, "A strictest"))
                            add("minScore", numProp("0-1, suggested 0.5", min = 0.0, max = 1.0))
                            add("dateString", JsonObject().apply {
                                addProperty("type", "string")
                                addProperty("pattern", "^\\d{4}-\\d{2}-\\d{2}$")
                                addProperty("description",
                                    "YYYY-MM-DD. Only if user names a specific date. NOT today by default.")
                            })
                            add("withinDays", intProp("Last N days; only if user named a range", min = 1))
                            add("excludeIds", JsonObject().apply {
                                addProperty("type", "array")
                                add("items", strProp(null))
                                addProperty("description", "Pagination: ids already seen")
                            })
                            add("excludeRecentEcho", boolProp("Default true; skip recent echo"))
                            add("includeFacts", boolProp("Return memory_facts, default false"))
                        })
                    })
                })
            }
        }

        /** OpenAI tool schema for memory-correct. */
        val CORRECT_MEMORY_TOOL_SCHEMA: JsonObject by lazy {
            JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", TOOL_CORRECT_MEMORY)
                    addProperty(
                        "description",
                        "Correct / delete / mark low-quality memory after search_memory finds " +
                            "errors, conflicts, stale or noisy data. All actions are irreversible " +
                            "except update. Always include reason for audit log."
                    )
                    add("parameters", JsonObject().apply {
                        addProperty("type", "object")
                        add("required", JsonArray().apply { add("action") })
                        add("properties", JsonObject().apply {
                            add("action", enumProp(CORRECT_ACTIONS, "Action type"))
                            add("memoryId", strProp("Target id for single-item actions"))
                            add("memoryIds", JsonObject().apply {
                                addProperty("type", "array")
                                add("items", strProp(null))
                                addProperty("maxItems", 50)
                                addProperty("description", "delete_batch only, max 50")
                            })
                            add("newContent", strProp("update only"))
                            add("quality", enumProp(QUALITY_GRADES, "set_quality only"))
                            add("factKey", strProp("snake_case"))
                            add("factValue", strProp("≤50 chars"))
                            add("factConfidence", numProp("0-1, default 0.8", min = 0.0, max = 1.0))
                            add("reason", strProp("Audit log message"))
                        })
                    })
                })
            }
        }

        // ─────────── schema 构造 helpers ───────────

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

        private fun numProp(description: String, min: Double? = null, max: Double? = null): JsonObject =
            JsonObject().apply {
                addProperty("type", "number")
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

        fun build(context: Context, assistantId: String?, sessionId: String?): ToolBridge? {
            val cfg = RemoteSyncConfigStore(context)
            if (!cfg.isSearchMemoryToolEnabled()) return null
            val baseUrl = cfg.getBaseUrl()
            val apiKey = cfg.getApiKey()
            val aid = assistantId?.trim().orEmpty()
            val sid = sessionId?.trim().orEmpty()
            // apiKey 允许为空 (与 RemoteSyncConfigStore.isReady() 策略一致):
            // 不强制 apiKey 非空, server 端可决定是否要鉴权.
            if (aid.isEmpty() || baseUrl.isEmpty()) return null
            return ToolBridge(aid, sid, baseUrl, apiKey)
        }
    }
}
