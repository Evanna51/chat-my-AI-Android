package com.example.aichat.chat

import com.google.gson.JsonObject

/**
 * Defensive Gson getters used throughout ChatService and the streaming pipeline.
 * Pulled out of ChatService.kt as part of Phase B refactor — every helper here
 * was previously a private fun inside ChatService, copy-pasted in identical
 * form. Behavior is unchanged: every getter swallows reflection / cast errors
 * and returns a safe zero value, which is intentional given the wide variety
 * of upstream JSON shapes (OpenAI, Gemini, llama.cpp, lmstudio, ...).
 */
object ChatJsonHelpers {

    fun getInt(obj: JsonObject?, key: String): Int {
        if (obj == null) return 0
        return try {
            val e = obj.get(key)
            if (e == null || e.isJsonNull) 0 else e.asInt
        } catch (_: Exception) {
            0
        }
    }

    fun getString(obj: JsonObject?, key: String): String {
        if (obj == null) return ""
        return try {
            val e = obj.get(key)
            if (e == null || e.isJsonNull) "" else e.asString
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Like [getString] but tolerant of `JsonObject` / `JsonArray` values —
     * some gateways encode reasoning / content as nested structures. Falls
     * back to `JsonElement.toString()` so the textual form is preserved.
     */
    fun getStringFlexible(obj: JsonObject?, key: String?): String {
        return try {
            if (obj == null || key == null || key.isEmpty()) return ""
            val e = obj.get(key) ?: return ""
            if (e.isJsonNull) return ""
            if (e.isJsonPrimitive) e.asString else e.toString()
        } catch (_: Exception) {
            ""
        }
    }

    fun firstNonEmpty(vararg values: String?): String {
        for (one in values) {
            if (one != null && one.isNotEmpty()) return one
        }
        return ""
    }
}
