package com.example.aichat.sync

import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Calls wi-chat-server's POST /api/tool/memory-recall.
 *
 * Request body shape (per server schema, only `query` is exposed to the LLM;
 * `assistantId` / `sessionId` are injected by the client):
 *   { assistantId, query, sessionId?, source?, category?, minQuality?, topK? }
 *
 * Response includes `memories: [{ id, content, memoryType, category, quality,
 * createdAt, score }]`. Returned as JSON string for direct insertion into a
 * `role=tool` message.
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
    fun memoryRecall(
        assistantId: String,
        sessionId: String?,
        query: String,
        topK: Int? = null,
        source: String? = null,
    ): String {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(assistantId.isNotEmpty()) { "assistantId required" }
        require(query.isNotEmpty()) { "query required" }
        val body = JsonObject().apply {
            addProperty("assistantId", assistantId)
            addProperty("query", query)
            if (!sessionId.isNullOrEmpty()) addProperty("sessionId", sessionId)
            if (topK != null) addProperty("topK", topK)
            if (!source.isNullOrEmpty()) addProperty("source", source)
        }
        val req = Request.Builder()
            .url("$baseUrl/api/tool/memory-recall")
            .header("x-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("memory-recall HTTP ${resp.code}: ${text.take(256)}")
            }
            return text
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
