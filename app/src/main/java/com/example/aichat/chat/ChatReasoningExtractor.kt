package com.example.aichat.chat

import com.example.aichat.chat.ChatJsonHelpers.firstNonEmpty
import com.example.aichat.chat.ChatJsonHelpers.getStringFlexible
import com.google.gson.JsonObject

/**
 * Extracts the reasoning / chain-of-thought delta from a streaming SSE
 * payload, handling the half-dozen field names different providers use:
 *   - `reasoning_content` (DeepSeek, Qwen)
 *   - `reasoning`         (OpenRouter, some Anthropic gateways)
 *   - `thinking`          (Gemini, llama.cpp)
 *
 * Pulled out of ChatService.kt as part of Phase B refactor; behavior is
 * unchanged. The extractor scans (in priority order):
 *   1. The current `delta` (streaming chunk)
 *   2. The current `choice` and its `message`
 *   3. The root SSE chunk
 * Returns the first non-empty match, or "" if no reasoning is present.
 */
object ChatReasoningExtractor {

    fun extract(root: JsonObject, choice: JsonObject?, delta: JsonObject?): String {
        var v = firstNonEmpty(
            getStringFlexible(delta, "reasoning_content"),
            getStringFlexible(delta, "reasoning"),
            getStringFlexible(delta, "thinking")
        )
        if (v.isNotEmpty()) return v

        val messageObj = if (choice != null && choice.has("message") && choice.get("message").isJsonObject)
            choice.getAsJsonObject("message") else null
        v = firstNonEmpty(
            getStringFlexible(choice, "reasoning_content"),
            getStringFlexible(choice, "reasoning"),
            getStringFlexible(choice, "thinking"),
            getStringFlexible(messageObj, "reasoning_content"),
            getStringFlexible(messageObj, "reasoning"),
            getStringFlexible(messageObj, "thinking"),
            getStringFlexible(root, "reasoning_content"),
            getStringFlexible(root, "reasoning"),
            getStringFlexible(root, "thinking")
        )
        return v
    }
}
