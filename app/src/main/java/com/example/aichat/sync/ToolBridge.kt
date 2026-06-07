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
 *   - `web_search`     → POST /api/tool/web-search（Tavily 后端，每角色每日 3 次配额）
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
    /** writer 模式才把 Story Tools 注入 — 由 build() 工厂决定。 */
    private val storyToolsEnabled: Boolean = false,
    /** Story Tools 需要 Context 操作本地 SessionOutlineStore; 仅 storyToolsEnabled 时必须非空。 */
    private val appContext: android.content.Context? = null,
) {

    fun isReady(): Boolean {
        if (assistantId.isEmpty()) return false
        // story-only 场景 (writer + 远程关) 也算 ready, 让 LLM 能调本地 story tools
        return baseUrl.isNotEmpty() || storyToolsEnabled
    }

    /** OpenAI-style tool descriptors injected into the chat request. */
    fun toolsJson(): JsonArray = JsonArray().apply {
        add(SEARCH_MEMORY_TOOL_SCHEMA)
        add(CORRECT_MEMORY_TOOL_SCHEMA)
        add(WEB_SEARCH_TOOL_SCHEMA)
        if (storyToolsEnabled) {
            for (schema in com.example.aichat.story.StoryToolSchemas.ALL) add(schema)
        }
    }

    /**
     * Execute a tool call. Returns the tool message content (typically the raw
     * server JSON) or an error string. Never throws; failures are surfaced to
     * the LLM as the tool result so it can recover.
     */
    fun invoke(toolName: String, argumentsJson: String): String {
        return try {
            when {
                toolName == TOOL_SEARCH_MEMORY -> invokeSearchMemory(argumentsJson)
                toolName == TOOL_CORRECT_MEMORY -> invokeCorrectMemory(argumentsJson)
                toolName == TOOL_WEB_SEARCH -> invokeWebSearch(argumentsJson)
                com.example.aichat.story.StoryToolHandler.isStoryTool(toolName) -> {
                    if (!storyToolsEnabled || appContext == null) {
                        errorJson("disabled", "story tools not enabled for this session")
                    } else {
                        com.example.aichat.story.StoryToolHandler
                            .invoke(appContext, sessionId, toolName, argumentsJson)
                    }
                }
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

    private fun invokeWebSearch(argumentsJson: String): String {
        val args = parseArgs(argumentsJson) ?: return errorJson("bad_arguments", "invalid JSON")
        val queryEl = args.get("query")
        val query = if (queryEl == null || queryEl.isJsonNull) "" else queryEl.asString
        if (query.trim().isEmpty()) return errorJson("bad_arguments", "missing 'query'")
        return MemoryToolApi(baseUrl, apiKey).webSearch(assistantId, args)
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
        const val TOOL_WEB_SEARCH = "web_search"

        private val CATEGORY_VALUES = listOf(
            "chitchat", "personal_experience", "relationship_info", "knowledge",
            "goals_plans", "preferences", "decisions_reflections", "wellbeing", "ideas"
        )
        // 与 server ALLOWED_MEMORY_TYPES (src/db.js) 保持同步.
        // CR-03: assistant_turn 已移除; tool_call/tool_result/system_event 从未存在过.
        private val MEMORY_TYPE_VALUES = listOf(
            "user_turn", "life_event", "work_event", "knowledge"
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
                            "source: 'user' (default) searches what user said in chats; " +
                            "'character' ONLY searches character's own generated narratives " +
                            "(very few entries — NOT user conversations). " +
                            "For recalling anything the user mentioned (experiences, feelings, " +
                            "events, opinions), always use 'user' or omit. " +
                            "Use 'all' if unsure whether info came from user or character.\n" +
                            "Time params: pass dateString only if user names a specific date " +
                            "(yesterday/3-13/etc, computed from user's words, NOT today). " +
                            "Pass withinDays only if user names a range (recent/last week). " +
                            "For attribute questions (likes/height/family) pass NO time param. " +
                            "When unsure, omit time params.\n" +
                            "If count=0 or no semantic match, tell user there is no record. Never fabricate."
                    )
                    // 参数精简到 6 个核心字段. 参数过多 (>6) 会让 DeepSeek/本地模型
                    // 倾向不调用 tool. 高级参数 (minQuality/excludeIds/memoryType 等)
                    // 由 bridge 层用默认值, LLM 不需要感知.
                    add("parameters", JsonObject().apply {
                        addProperty("type", "object")
                        add("required", JsonArray().apply { add("query") })
                        add("properties", JsonObject().apply {
                            add("query", strProp("Refined topic keywords, not full user sentence"))
                            add("topK", intProp("Number of results, 1-20, default 5", min = 1, max = 20))
                            add("source", enumProp(SOURCE_VALUES,
                                "user=user's own words (default); " +
                                "character=character inner narratives only; " +
                                "all=both"))
                            add("dateString", JsonObject().apply {
                                addProperty("type", "string")
                                addProperty("pattern", "^\\d{4}-\\d{2}-\\d{2}$")
                                addProperty("description",
                                    "YYYY-MM-DD. Only if user names a specific date.")
                            })
                            add("withinDays", intProp("Last N days, only if user names a range", min = 1))
                            add("includeFacts", boolProp("Return memory facts, default false"))
                        })
                    })
                })
            }
        }

        /**
         * OpenAI tool schema for web-search（Tavily 后端，每角色每日 3 次配额）。
         *
         * 用例：用户问当前事实 / 新闻 / 天气 / 热点；LLM 想分享一条外部信息。
         * 不要为闲聊 / 情绪 / RP / 一般百科类调用 —— 浪费配额。
         */
        val WEB_SEARCH_TOOL_SCHEMA: JsonObject by lazy {
            JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", TOOL_WEB_SEARCH)
                    addProperty(
                        "description",
                        "Search the web for current external facts: news, weather, market data, " +
                            "trending topics, or any info too recent / external to be in memory. " +
                            "Backed by Tavily; quota ~10 calls/day per assistant. " +
                            "Use when user asks about today/recent events, current data, or asks " +
                            "you to look up / recommend recent content. " +
                            "Do NOT use for casual chat, emotions, role-play, or encyclopedia-type " +
                            "questions you can answer from training — it wastes quota. " +
                            "Returns results[] with title/url/content; rephrase in character voice, " +
                            "don't recite titles. If ok=false with reason=daily_cap_exceeded, tell " +
                            "user gently you've already searched too much today."
                    )
                    add("parameters", JsonObject().apply {
                        addProperty("type", "object")
                        add("required", JsonArray().apply { add("query") })
                        add("properties", JsonObject().apply {
                            add("query", strProp("Search keywords (1-200 chars), Chinese or English"))
                            add("topic", enumProp(listOf("news", "general"),
                                "news (default, recent events) / general (broader, less time-sensitive)"))
                            add("maxResults", intProp("Number of results, 1-10, default 5", min = 1, max = 10))
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
            // Story tools 与 memory tools 各自可启用; 即使 memory 关了, writer session 也要能用 story tools
            val storyEnabled = isWriterSession(context, assistantId)
            val memoryEnabled = cfg.isSearchMemoryToolEnabled()
            if (!memoryEnabled && !storyEnabled) return null
            val baseUrl = cfg.getBaseUrl()
            val apiKey = cfg.getApiKey()
            val aid = assistantId?.trim().orEmpty()
            val sid = sessionId?.trim().orEmpty()
            // memory tools 需要 server 可达; story tools 完全本地不需要 baseUrl。
            // 但 isReady() 也只检查 assistantId / baseUrl — story-only 场景下 baseUrl 可能为空。
            // 简化策略: 只要 storyEnabled 或 memoryEnabled 至少一个 ready 就构造 bridge。
            if (aid.isEmpty()) return null
            if (!storyEnabled && baseUrl.isEmpty()) return null
            return ToolBridge(
                assistantId = aid,
                sessionId = sid,
                baseUrl = baseUrl,
                apiKey = apiKey,
                storyToolsEnabled = storyEnabled,
                appContext = if (storyEnabled) context.applicationContext else null,
            )
        }

        /** 判断当前 session 是不是 writer 模式 — 决定要不要注入 Story Tools。 */
        private fun isWriterSession(context: Context, assistantId: String?): Boolean {
            val aid = assistantId?.trim().orEmpty()
            if (aid.isEmpty()) return false
            return try {
                val assistant = com.example.aichat.MyAssistantStore(context).getById(aid) ?: return false
                assistant.type == "writer"
            } catch (_: Throwable) { false }
        }
    }
}
