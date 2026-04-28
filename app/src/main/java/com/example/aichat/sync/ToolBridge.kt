package com.example.aichat.sync

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Bridges client-side tool definitions exposed to the LLM with their concrete
 * server-side handlers (currently only `search_memory` → wi-chat-server's
 * `/api/tool/memory-recall`).
 *
 * The bridge is constructed per chat dispatch with the session's bound
 * assistantId/sessionId so the LLM can stay agnostic of those — the tool schema
 * only exposes `query` to the model.
 */
class ToolBridge(
    private val assistantId: String,
    private val sessionId: String,
    private val baseUrl: String,
    private val apiKey: String,
) {

    fun isReady(): Boolean =
        assistantId.isNotEmpty() && baseUrl.isNotEmpty() && apiKey.isNotEmpty()

    /** OpenAI-style tool descriptors injected into the chat request. */
    fun toolsJson(): JsonArray = JsonArray().apply {
        add(SEARCH_MEMORY_TOOL_SCHEMA)
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
                else -> errorJson("unknown_tool", "tool '$toolName' is not registered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "tool '$toolName' failed", e)
            errorJson("tool_failure", e.message ?: "unknown error")
        }
    }

    private fun invokeSearchMemory(argumentsJson: String): String {
        val args = try {
            com.google.gson.JsonParser().parse(argumentsJson).asJsonObject
        } catch (e: Exception) {
            return errorJson("bad_arguments", "tool arguments not valid JSON: ${e.message}")
        }
        val queryEl: com.google.gson.JsonElement? = args.get("query")
        val query = if (queryEl == null || queryEl.isJsonNull) "" else queryEl.asString
        if (query.trim().isEmpty()) {
            return errorJson("bad_arguments", "missing 'query'")
        }
        val api = MemoryToolApi(baseUrl, apiKey)
        return api.memoryRecall(
            assistantId = assistantId,
            sessionId = sessionId,
            query = query.trim(),
        )
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

        /** OpenAI tool schema. Only `query` is visible to the model. */
        val SEARCH_MEMORY_TOOL_SCHEMA: JsonObject by lazy {
            JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", TOOL_SEARCH_MEMORY)
                    addProperty(
                        "description",
                        "Search the user's personal knowledge base, including past conversations, " +
                            "notes, and project records. Use this tool when the user refers to " +
                            "past experiences, previous discussions, or personal context that is " +
                            "not present in the current conversation."
                    )
                    add("parameters", JsonObject().apply {
                        addProperty("type", "object")
                        add("properties", JsonObject().apply {
                            add("query", JsonObject().apply {
                                addProperty("type", "string")
                                addProperty(
                                    "description",
                                    "A rewritten, explicit search query optimized for semantic " +
                                        "retrieval. Expand vague references into concrete terms."
                                )
                            })
                        })
                        add("required", JsonArray().apply { add("query") })
                    })
                })
            }
        }

        fun build(context: Context, assistantId: String?, sessionId: String?): ToolBridge? {
            val cfg = RemoteSyncConfigStore(context)
            if (!cfg.isSearchMemoryToolEnabled()) return null
            val baseUrl = cfg.getBaseUrl()
            val apiKey = cfg.getApiKey()
            val aid = assistantId?.trim().orEmpty()
            val sid = sessionId?.trim().orEmpty()
            if (aid.isEmpty() || baseUrl.isEmpty() || apiKey.isEmpty()) return null
            return ToolBridge(aid, sid, baseUrl, apiKey)
        }
    }
}
