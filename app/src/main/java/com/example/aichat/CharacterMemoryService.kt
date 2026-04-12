package com.example.aichat

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CharacterMemoryService(context: Context) {

    private val configStore: CharacterMemoryConfigStore = CharacterMemoryConfigStore(context)

    fun isEnabled(): Boolean = configStore.isEnabled()

    @Throws(Exception::class)
    fun getMemoryContext(
        assistantId: String?,
        sessionId: String?,
        userMessage: String?
    ): CharacterMemoryApi.MemoryContextResponse {
        val body = CharacterMemoryApi.MemoryContextRequest()
        body.assistantId = safeTrim(assistantId)
        body.sessionId = safeTrim(sessionId)
        val safeInput = safeTrim(userMessage)
        body.userInput = safeInput
        body.userMessage = safeInput

        val raw = postJson(CharacterMemoryApi.PATH_MEMORY_CONTEXT, GSON.toJson(body))
        return parseMemoryContextResponse(raw)
    }

    @Throws(Exception::class)
    fun reportInteraction(
        assistantId: String?,
        sessionId: String?,
        role: String?,
        content: String?
    ) {
        val body = CharacterMemoryApi.ReportInteractionRequest()
        body.assistantId = safeTrim(assistantId)
        body.sessionId = safeTrim(sessionId)
        body.role = safeTrim(role)
        body.content = safeTrim(content)
        postJson(CharacterMemoryApi.PATH_REPORT_INTERACTION, GSON.toJson(body))
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
    fun pullMessages(since: String?, limit: Int): CharacterMemoryApi.PullMessagesResponse {
        val query = StringBuilder()
        query.append("userId=default-user")
        if (!since?.trim().isNullOrEmpty()) {
            query.append("since=").append(urlEncode(since!!.trim()))
        }
        if (limit > 0) {
            if (query.isNotEmpty()) query.append("&")
            query.append("limit=").append(limit)
        }
        val raw = getJson(CharacterMemoryApi.PATH_PULL_MESSAGES, query.toString())
        return parsePullMessagesResponse(raw)
    }

    @Throws(Exception::class)
    fun ackMessage(messageId: String?, ackStatus: String?): CharacterMemoryApi.AckMessageResponse {
        val body = CharacterMemoryApi.AckMessageRequest()
        body.messageId = safeTrim(messageId)
        body.ackStatus = safeTrim(ackStatus)
        val raw = postJson(CharacterMemoryApi.PATH_ACK_MESSAGE, GSON.toJson(body))
        return parseAckMessageResponse(raw)
    }

    @Throws(Exception::class)
    private fun postJson(path: String, jsonBody: String): String {
        return executeJsonRequest("POST", path, null, jsonBody)
    }

    @Throws(Exception::class)
    private fun getJson(path: String, queryString: String?): String {
        return executeJsonRequest("GET", path, queryString, null)
    }

    @Throws(Exception::class)
    private fun executeJsonRequest(method: String, path: String, queryString: String?, jsonBody: String?): String {
        val baseUrl = normalizeBaseUrl(configStore.getBaseUrl())
        val apiKey = safeTrim(configStore.getApiKey())
        var url = baseUrl + path
        if (!queryString?.trim().isNullOrEmpty()) {
            url = "$url?${queryString!!.trim()}"
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(configStore.getConnectTimeoutMs().toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(configStore.getReadTimeoutMs().toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(configStore.getReadTimeoutMs().toLong(), TimeUnit.MILLISECONDS)
            .build()

        val requestBuilder = Request.Builder().url(url)
        if ("POST".equals(method, ignoreCase = true)) {
            requestBuilder.post((jsonBody ?: "{}").toRequestBody(JSON))
            requestBuilder.addHeader("Content-Type", "application/json")
        } else {
            requestBuilder.get()
        }
        if (apiKey.isNotEmpty()) {
            requestBuilder.addHeader("x-api-key", apiKey)
        }
        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            val body: String
            response.body.use { rb ->
                body = rb?.string() ?: ""
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $body")
            }
            if (configStore.isDebugLogEnabled()) {
                Log.d(TAG, "$method $path ok, response=$body")
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
            out.shouldUseMemory = getBoolean(obj, "shouldUseMemory")
            out.reason = getString(obj, "reason")
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

    private fun parsePullMessagesResponse(raw: String?): CharacterMemoryApi.PullMessagesResponse {
        val out = CharacterMemoryApi.PullMessagesResponse()
        if (raw?.trim().isNullOrEmpty()) return out
        try {
            val obj = JsonParser().parse(raw).asJsonObject
            out.ok = getBoolean(obj, "ok")
            out.userId = getString(obj, "userId")
            out.since = getString(obj, "since")
            out.count = getInt(obj, "count")
            out.now = getString(obj, "now")
            val arr: JsonArray? = if (obj.has("messages") && obj.get("messages").isJsonArray)
                obj.getAsJsonArray("messages") else null
            if (arr != null) {
                val list = ArrayList<CharacterMemoryApi.PulledMessage>()
                for (one in arr) {
                    if (one == null || !one.isJsonObject) continue
                    val item = one.asJsonObject
                    val m = CharacterMemoryApi.PulledMessage()
                    m.id = getString(item, "id")
                    m.assistantId = getString(item, "assistantId")
                    m.sessionId = getString(item, "sessionId")
                    m.messageType = getString(item, "messageType")
                    m.title = getString(item, "title")
                    m.body = getString(item, "body")
                    m.payload = getJsonObject(item, "payload")
                    m.createdAt = getString(item, "createdAt")
                    m.availableAt = getString(item, "availableAt")
                    m.expiresAt = getString(item, "expiresAt")
                    m.pullCount = getInt(item, "pullCount")
                    list.add(m)
                }
                out.messages = list
                if (out.count <= 0) out.count = list.size
            }
        } catch (ignored: Exception) {}
        return out
    }

    private fun parseAckMessageResponse(raw: String?): CharacterMemoryApi.AckMessageResponse {
        val out = CharacterMemoryApi.AckMessageResponse()
        if (raw?.trim().isNullOrEmpty()) return out
        try {
            val obj = JsonParser().parse(raw).asJsonObject
            out.ok = getBoolean(obj, "ok")
            out.messageId = getString(obj, "messageId")
            out.ackStatus = getString(obj, "ackStatus")
        } catch (ignored: Exception) {}
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

    private fun getInt(obj: JsonObject, key: String): Int {
        return try {
            val e: JsonElement? = obj.get(key)
            if (e == null || e.isJsonNull) 0 else e.asInt
        } catch (ignored: Exception) {
            0
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

    private fun getJsonObject(obj: JsonObject, key: String): JsonObject {
        return try {
            val e: JsonElement? = obj.get(key)
            if (e == null || e.isJsonNull || !e.isJsonObject) JsonObject() else e.asJsonObject
        } catch (ignored: Exception) {
            JsonObject()
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

    private fun urlEncode(text: String): String {
        return try {
            URLEncoder.encode(text, "UTF-8")
        } catch (ignored: Exception) {
            text
        }
    }

    companion object {
        private const val TAG = "CharacterMemoryService"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val GSON = Gson()
    }
}
