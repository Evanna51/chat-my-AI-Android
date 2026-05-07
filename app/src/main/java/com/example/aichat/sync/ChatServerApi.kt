package com.example.aichat.sync

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client for wi-chat-server sync endpoints. Synchronous; designed
 * to be called from a background executor / WorkManager / coroutine on IO.
 */
class ChatServerApi(
    private val baseUrl: String,
    private val apiKey: String,
    timeoutSeconds: Long = 10L,
) {
    private val gson = Gson()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    /**
     * GET /api/health — used by HomeNetworkCallback to gate WS / drain on entry.
     */
    fun health(): Boolean {
        if (baseUrl.isEmpty()) return false
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/health")
                .get()
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    /**
     * POST /api/sync/push — batch upload turns. Response is parsed into
     * [SyncPushResponse]; on transport error, an exception is thrown.
     */
    @Throws(IOException::class)
    fun syncPush(request: SyncPushRequest): SyncPushResponse {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        val body = gson.toJson(request).toRequestBody(JSON)
        val req = Request.Builder()
            .url("$baseUrl/api/sync/push")
            .header("x-api-key", apiKey)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, text.take(512))
            }
            return parseResponse(text, SyncPushResponse::class.java)
                ?: throw IOException("empty push response")
        }
    }

    /**
     * POST /api/sync/snapshot — 一次性同步: 上传 assistants 元数据 + 一批 turns.
     * 响应复用 [SyncPushResponse].
     */
    @Throws(IOException::class)
    fun snapshotPush(request: SnapshotPushRequest): SyncPushResponse {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        val body = gson.toJson(request).toRequestBody(JSON)
        val req = Request.Builder()
            .url("$baseUrl/api/sync/snapshot")
            .header("x-api-key", apiKey)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, text.take(512))
            }
            return parseResponse(text, SyncPushResponse::class.java)
                ?: throw IOException("empty snapshot response")
        }
    }

    /**
     * GET /api/character/bootstrap — session 启动 / 每日固定调一次,
     * 一次拿齐拼 system prompt 的 relationshipState + coreMemories + coreFacts.
     * 不做语义检索 (那是 memory-context / search_memory 的事).
     * 返回 raw JSON, 客户端层解析成 [BootstrapPayload].
     */
    @Throws(IOException::class)
    fun characterBootstrap(assistantId: String): String {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(assistantId.isNotEmpty()) { "assistantId required" }
        val url = "$baseUrl/api/character/bootstrap?assistantId=${urlEncode(assistantId)}"
        val builder = Request.Builder().url(url).get()
        if (apiKey.isNotEmpty()) builder.header("x-api-key", apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, text.take(512))
            }
            return text
        }
    }

    /**
     * GET /api/sync/state — pull server-side counters for cross-checking.
     */
    @Throws(IOException::class)
    fun syncState(assistantId: String?, deviceId: String?): SyncStateResponse {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        val builder = StringBuilder("$baseUrl/api/sync/state")
        val params = mutableListOf<String>()
        if (!assistantId.isNullOrEmpty()) params.add("assistantId=${urlEncode(assistantId)}")
        if (!deviceId.isNullOrEmpty()) params.add("deviceId=${urlEncode(deviceId)}")
        if (params.isNotEmpty()) builder.append('?').append(params.joinToString("&"))
        val req = Request.Builder()
            .url(builder.toString())
            .header("x-api-key", apiKey)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, text.take(512))
            }
            return parseResponse(text, SyncStateResponse::class.java)
                ?: throw IOException("empty state response")
        }
    }

    private fun <T> parseResponse(text: String, type: Class<T>): T? = try {
        gson.fromJson(text, type)
    } catch (e: Exception) {
        throw IOException("malformed response: ${e.message}")
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    class HttpStatusException(val statusCode: Int, val responseBody: String) :
        IOException("HTTP $statusCode: $responseBody")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
