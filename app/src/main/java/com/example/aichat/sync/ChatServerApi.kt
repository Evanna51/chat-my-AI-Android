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
 *
 * Phase 2 端点（client lifecycle 视角，见 docs/api-redesign-plan.md §3 + §6 Phase 2）：
 *   App 启动 / 切换角色      → [getCharacter]
 *   每轮发消息前 hot path    → [chatContext]
 *   发完一轮上传             → [chatTurn]
 *   删除一条消息             → [deleteChatTurn]
 *
 * 兼容端点：[characterContext]（admin / debug / boot cache 用），[snapshotPush]，
 * [syncState]。已删除：syncPush / characterBootstrap（Phase 2 cleanup）。
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

    // ── Phase 2: client-lifecycle endpoints ─────────────────────────────

    /**
     * GET /api/character/{assistantId} — App 启动 / 切换角色时拉静态 slots（取代 bootstrap）。
     * 返回 profile + identity + 5 个 rendered slot（role / character / background /
     * constraints / tool_protocol）+ etag。客户端长缓存到 etag 失效。
     */
    @Throws(IOException::class)
    fun getCharacter(assistantId: String): ChatCharacterResponse {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(assistantId.isNotEmpty()) { "assistantId required" }
        val url = "$baseUrl/api/character/${urlEncode(assistantId)}"
        val builder = Request.Builder().url(url).get()
        if (apiKey.isNotEmpty()) builder.header("x-api-key", apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, text.take(512))
            }
            return parseResponse(text, ChatCharacterResponse::class.java)
                ?: throw IOException("empty character response")
        }
    }

    /**
     * POST /api/chat/context — 每轮发消息前 hot path（取代 character/context + memory-context）。
     * 一次返回：facts slot（含 coreFacts + retrieved）+ narrative slot + assistantPrefill +
     * memoryDecision + etag（如失配附 renderedSlots）。
     *
     * 客户端 merge 顺序：role + character + background + constraints + facts + narrative
     * + <client>(客户端本地追加) + tool_protocol，末尾 assistantPrefill。详见
     * docs/client-prompt-merge-protocol.md。
     */
    @Throws(IOException::class)
    fun chatContext(request: ChatContextRequest): ChatContextResponse {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(request.assistantId.isNotEmpty()) { "assistantId required" }
        require(request.sessionId.isNotEmpty()) { "sessionId required" }
        val body = gson.toJson(request).toRequestBody(JSON)
        val builder = Request.Builder()
            .url("$baseUrl/api/chat/context")
            .post(body)
        if (apiKey.isNotEmpty()) builder.header("x-api-key", apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, text.take(512))
            }
            return parseResponse(text, ChatContextResponse::class.java)
                ?: throw IOException("empty chat context response")
        }
    }

    /**
     * POST /api/chat/turn — 上传一轮（语义化别名 /api/sync/push，行为完全等价）。
     * server 内部走同一个 ingestTurnsBatch；用同一个客户端 turn UUID 即可幂等。
     */
    @Throws(IOException::class)
    fun chatTurn(request: ChatTurnRequest): ChatTurnResponse {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(request.turns.isNotEmpty()) { "turns required" }
        val body = gson.toJson(request).toRequestBody(JSON)
        val builder = Request.Builder()
            .url("$baseUrl/api/chat/turn")
            .post(body)
        if (apiKey.isNotEmpty()) builder.header("x-api-key", apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, text.take(512))
            }
            return parseResponse(text, ChatTurnResponse::class.java)
                ?: throw IOException("empty chat turn response")
        }
    }

    /**
     * DELETE /api/chat/turn/{turnId} — 删除一条消息 + cascade（含衍生 memory_items / facts /
     * episode_links 等清理 + 触发 state 重算 + WS 推 turn_deleted 给所有客户端）。
     */
    @Throws(IOException::class)
    fun deleteChatTurn(turnId: String): DeleteTurnResponse {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(turnId.isNotEmpty()) { "turnId required" }
        val url = "$baseUrl/api/chat/turn/${urlEncode(turnId)}"
        val builder = Request.Builder().url(url).delete()
        if (apiKey.isNotEmpty()) builder.header("x-api-key", apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && resp.code != 404) {
                throw HttpStatusException(resp.code, text.take(512))
            }
            return parseResponse(text, DeleteTurnResponse::class.java)
                ?: throw IOException("empty delete response")
        }
    }

    // ── 兼容端点 ────────────────────────────────────────────────────────
    // /api/sync/push 已于 Phase 2 删除（语义化为 chatTurn，行为等价）。
    // /api/sync/snapshot 保留 — assistants + turns 一次性同步，与 chat/turn 不同语义。

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
     * POST /api/character/context — admin / debug / boot cache 端点。
     * 返回 7 层认知态 payload + renderedSlots（role/character/background/constraints/toolProtocol）。
     * 不带本轮 user 上下文（无 sessionId / userInput）；facts / narrative 是占位。
     *
     * 主要用于 [CharacterBootstrapStore] boot 时缓存 renderedSlots，让首条消息延迟低。
     */
    @Throws(IOException::class)
    fun characterContext(assistantId: String, lastUserMessage: String? = null): String {
        require(baseUrl.isNotEmpty()) { "baseUrl not configured" }
        require(assistantId.isNotEmpty()) { "assistantId required" }
        val body = com.google.gson.JsonObject().apply {
            addProperty("assistantId", assistantId)
            if (!lastUserMessage.isNullOrEmpty()) addProperty("lastUserMessage", lastUserMessage)
        }
        val builder = Request.Builder()
            .url("$baseUrl/api/character/context")
            .post(body.toString().toRequestBody(JSON))
        if (apiKey.isNotEmpty()) builder.header("x-api-key", apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, text.take(512))
            }
            return text
        }
    }

    // /api/character/bootstrap 已于 Phase 2 删除（dev 客户端，无兼容包袱）。
    // 客户端走 [getCharacter]（合并 profile + identity + etag-able 静态 slots）。

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
