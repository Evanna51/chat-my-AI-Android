package com.example.aichat.chat

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Accumulates streaming OpenAI-style tool_calls deltas across multiple SSE
 * chunks (id / type / function.name / function.arguments arrive piecemeal)
 * and builds the assistant + tool messages required for the next chat
 * round.
 *
 * Pulled out of ChatService.kt as part of Phase B refactor; behavior is
 * unchanged.
 */
class ToolCallBuilder {
    var id: String = ""
    var type: String = "function"
    var name: String = ""
    val argumentsBuilder: StringBuilder = StringBuilder()
}

object ChatToolCallAccumulator {

    /**
     * Merge a streaming `tool_calls` delta array into [accum], keyed by
     * the delta's `index` field (or array index as fallback). String fields
     * (`id`, `type`, `function.name`) overwrite when non-empty; arguments
     * are appended (LLM streams partial JSON).
     */
    fun accumulateDelta(deltas: JsonArray, accum: LinkedHashMap<Int, ToolCallBuilder>) {
        for (i in 0 until deltas.size()) {
            val el = deltas[i]
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val index = if (obj.has("index") && obj.get("index").isJsonPrimitive)
                obj.get("index").asInt else i
            val builder = accum.getOrPut(index) { ToolCallBuilder() }
            if (obj.has("id") && obj.get("id").isJsonPrimitive) {
                val newId = obj.get("id").asString
                if (newId.isNotEmpty()) builder.id = newId
            }
            if (obj.has("type") && obj.get("type").isJsonPrimitive) {
                builder.type = obj.get("type").asString
            }
            if (obj.has("function") && obj.get("function").isJsonObject) {
                val fn = obj.getAsJsonObject("function")
                if (fn.has("name") && fn.get("name").isJsonPrimitive) {
                    val newName = fn.get("name").asString
                    if (newName.isNotEmpty()) builder.name = newName
                }
                if (fn.has("arguments") && fn.get("arguments").isJsonPrimitive) {
                    builder.argumentsBuilder.append(fn.get("arguments").asString)
                }
            }
        }
    }

    /**
     * Build the OpenAI-style `assistant` message that wraps a list of
     * accumulated tool_calls. content is `null` per OpenAI spec.
     */
    fun buildAssistantToolCallMessage(calls: List<ToolCallBuilder>): JsonObject = JsonObject().apply {
        addProperty("role", "assistant")
        add("content", JsonNull.INSTANCE)
        val arr = JsonArray()
        for (tc in calls) {
            arr.add(JsonObject().apply {
                addProperty("id", tc.id.ifEmpty { "call_${System.nanoTime()}" })
                addProperty("type", tc.type.ifEmpty { "function" })
                add("function", JsonObject().apply {
                    addProperty("name", tc.name)
                    addProperty("arguments", tc.argumentsBuilder.toString())
                })
            })
        }
        add("tool_calls", arr)
    }

    /**
     * Build the `tool` role message carrying a single tool execution result.
     * [callId] should be the id from the assistant's tool_call entry; a
     * synthetic `call_<nanoTime>` is substituted only when the upstream
     * payload omitted one.
     */
    fun buildToolResultMessage(callId: String, name: String, content: String): JsonObject =
        JsonObject().apply {
            addProperty("role", "tool")
            addProperty("tool_call_id", callId.ifEmpty { "call_${System.nanoTime()}" })
            addProperty("name", name)
            addProperty("content", content)
        }
}
