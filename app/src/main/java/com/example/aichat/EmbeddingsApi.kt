package com.example.aichat

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object EmbeddingsApi {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun embed(apiHost: String?, apiPath: String?, apiKey: String?, model: String, input: String): FloatArray? {
        if (apiHost.isNullOrBlank()) return null
        val host = apiHost.trimEnd('/')
        val rawPath = (apiPath ?: "").trim()
        val path = if (rawPath.isBlank() || rawPath.contains("chat/completions")) "/embeddings"
                   else if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        val url = host + path
        val body = "{\"model\":${quote(model)},\"input\":${quote(input)}}"
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
        // 本地 provider 通常无鉴权; 仅在有 key 时加 Authorization header.
        if (!apiKey.isNullOrBlank()) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }
        val req = builder.build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                val root = JsonParser().parse(text).asJsonObject
                val arr = root.getAsJsonArray("data") ?: return null
                if (arr.size() == 0) return null
                val embArr = arr.get(0).asJsonObject.getAsJsonArray("embedding") ?: return null
                FloatArray(embArr.size()) { i -> embArr.get(i).asFloat }
            }
        } catch (e: Exception) { null }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
