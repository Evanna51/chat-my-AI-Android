package com.example.aichat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class CharacterMemoryService(context: Context) {

    // Unified with the remote sync settings page — this service now reads from
    // the same SharedPreferences as the wi-chat-server sync feature.
    private val configStore: com.example.aichat.sync.RemoteSyncConfigStore =
        com.example.aichat.sync.RemoteSyncConfigStore(context)

    fun isEnabled(): Boolean = configStore.isEnabled()

    /**
     * 调 /api/chat/context（Phase 2 取代旧 /api/tool/memory-context）。
     * server 内部仍跑 retrieve decision + retrieval；返回的 ChatContextResponse 含
     * memoryDecision.shouldRetrieve / memoryLines / memoryGuidance —— 这里保持
     * MemoryContextResponse 旧 schema 以最小化 caller 改动。
     */
    @Throws(Exception::class)
    fun getMemoryContext(
        assistantId: String?,
        sessionId: String?,
        userMessage: String?
    ): CharacterMemoryApi.MemoryContextResponse {
        val safeInput = safeTrim(userMessage)
        if (safeInput.isEmpty()) {
            // chat/context 要求 userInput.min(1)
            return CharacterMemoryApi.MemoryContextResponse().apply {
                ok = false
                reason = "empty_user_input"
            }
        }
        val body = JsonObject().apply {
            addProperty("assistantId", safeTrim(assistantId))
            addProperty("sessionId", safeTrim(sessionId))
            addProperty("userInput", safeInput)
        }
        val raw = postJson(CharacterMemoryApi.PATH_CHAT_CONTEXT, GSON.toJson(body))
        return parseMemoryContextResponse(raw)
    }

    @Throws(Exception::class)
    fun reportCharacterProfile(
        assistantId: String?,
        characterName: String?,
        characterBackground: String?,
        allowAutoLife: Boolean,
        allowProactiveMessage: Boolean
    ) {
        val body = CharacterMemoryApi.CharacterProfileRequest()
        body.assistantId = safeTrim(assistantId)
        body.characterName = safeTrim(characterName)
        body.characterBackground = safeTrim(characterBackground)
        body.allowAutoLife = allowAutoLife
        body.allowProactiveMessage = allowProactiveMessage
        postJson(CharacterMemoryApi.PATH_REPORT_CHARACTER_PROFILE, GSON.toJson(body))
    }

    @Throws(Exception::class)
    private fun postJson(path: String, jsonBody: String): String {
        val baseUrl = normalizeBaseUrl(configStore.getBaseUrl())
        val apiKey = safeTrim(configStore.getApiKey())
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val builder = Request.Builder()
            .url(baseUrl + path)
            .post(jsonBody.toRequestBody(JSON))
            .addHeader("Content-Type", "application/json")
        if (apiKey.isNotEmpty()) {
            builder.addHeader("x-api-key", apiKey)
        }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $body")
            }
            return body
        }
    }

    private fun parseMemoryContextResponse(raw: String?): CharacterMemoryApi.MemoryContextResponse {
        val out = CharacterMemoryApi.MemoryContextResponse()
        if (raw?.trim().isNullOrEmpty()) return out
        try {
            val obj = JsonParser().parse(raw).asJsonObject
            out.ok = getBoolean(obj, "ok")
            // Phase 2: chat/context 用 memoryDecision.shouldRetrieve；旧 memory-context
            // 用顶层 shouldUseMemory。优先读新字段，fallback 旧字段（保护启动期不一致）。
            val mdObj = if (obj.has("memoryDecision") && obj.get("memoryDecision").isJsonObject)
                obj.getAsJsonObject("memoryDecision") else null
            out.shouldUseMemory = if (mdObj != null) getBoolean(mdObj, "shouldRetrieve") else getBoolean(obj, "shouldUseMemory")
            out.reason = if (mdObj != null) getString(mdObj, "reason") else getString(obj, "reason")
            out.memoryGuidance = getString(obj, "memoryGuidance")

            val lines: JsonArray? = if (obj.has("memoryLines") && obj.get("memoryLines").isJsonArray)
                obj.getAsJsonArray("memoryLines") else null
            if (lines != null) {
                val parsed = ArrayList<String>()
                for (one in lines) {
                    if (one == null || one.isJsonNull) continue
                    val line = one.asString
                    if (!line?.trim().isNullOrEmpty()) parsed.add(line!!.trim())
                }
                out.memoryLines = parsed
            }
            if (out.memoryGuidance?.trim().isNullOrEmpty()
                && out.memoryLines != null && out.memoryLines.isNotEmpty()) {
                out.memoryGuidance = "记忆参考: " + joinWithSeparator(out.memoryLines, " | ")
            }
        } catch (ignored: Exception) {
            out.reason = "invalid_json"
        }
        return out
    }

    private fun normalizeBaseUrl(source: String?): String {
        var base = safeTrim(source)
        if (base.isEmpty()) base = "http://127.0.0.1:8787"
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://$base"
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length - 1)
        }
        return base
    }

    private fun safeTrim(text: String?): String = text?.trim() ?: ""

    private fun getBoolean(obj: JsonObject, key: String): Boolean {
        return try {
            val e: JsonElement? = obj.get(key)
            if (e == null || e.isJsonNull) false else e.asBoolean
        } catch (ignored: Exception) {
            false
        }
    }

    private fun getString(obj: JsonObject, key: String): String {
        return try {
            val e: JsonElement? = obj.get(key)
            if (e == null || e.isJsonNull) "" else e.asString
        } catch (ignored: Exception) {
            ""
        }
    }

    private fun joinWithSeparator(items: List<String>, sep: String): String {
        val sb = StringBuilder()
        for (i in items.indices) {
            if (i > 0) sb.append(sep)
            sb.append(items[i])
        }
        return sb.toString()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 15_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val GSON = Gson()
    }
}
