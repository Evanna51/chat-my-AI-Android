package com.example.aichat.sync

import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Calls wi-chat-server's tool endpoints:
 *   - POST /api/tool/memory-recall   — search past memories
 *   - POST /api/tool/memory-correct  — edit / delete / set-quality / fact mutations
 *   - POST /api/tool/web-search      — Tavily-backed external search（每角色每日 3 次配额）
 *
 * `assistantId` (and optionally `sessionId`) are injected by the bridge — the LLM
 * never sees them. Other params are passed through verbatim from the LLM tool
 * arguments JSON; server validates the schema.
 *
 * Returns the raw server JSON as a string so the bridge can drop it directly
 * into a `role=tool` message.
 */
class MemoryToolApi(
    private val baseUrl: String,
    private val apiKey: String,
    timeoutSeconds: Long = 15L,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    fun memoryRecall(assistantId: String, sessionId: String?, args: JsonObject): String {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(assistantId.isNotEmpty()) { "assistantId required" }
        val body = args.deepCopy().apply {
            addProperty("assistantId", assistantId)
            if (!sessionId.isNullOrEmpty() && !has("sessionId")) {
                addProperty("sessionId", sessionId)
            }
        }
        return postJson("$baseUrl/api/tool/memory-recall", body.toString())
    }

    @Throws(IOException::class)
    fun memoryCorrect(assistantId: String, args: JsonObject): String {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(assistantId.isNotEmpty()) { "assistantId required" }
        val body = args.deepCopy().apply {
            addProperty("assistantId", assistantId)
        }
        return postJson("$baseUrl/api/tool/memory-correct", body.toString())
    }

    @Throws(IOException::class)
    fun webSearch(assistantId: String, args: JsonObject): String {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(assistantId.isNotEmpty()) { "assistantId required" }
        val body = args.deepCopy().apply {
            addProperty("assistantId", assistantId)
        }
        return postJson("$baseUrl/api/tool/web-search", body.toString())
    }

    @Throws(IOException::class)
    private fun postJson(url: String, body: String): String {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
        if (apiKey.isNotEmpty()) builder.header("x-api-key", apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${text.take(256)}")
            }
            return text
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
