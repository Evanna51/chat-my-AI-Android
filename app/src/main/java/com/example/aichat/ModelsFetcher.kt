package com.example.aichat

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 获取 OpenAI 兼容 API 的模型列表，参考 chatbox fetchRemoteModels
 */
object ModelsFetcher {
    private val GSON = Gson()
    private const val MAX_MODEL_PAGE_FETCH = 30
    private val CLIENT = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val EXECUTOR = Executors.newSingleThreadExecutor()
    private val MAIN = Handler(Looper.getMainLooper())

    interface Callback {
        fun onSuccess(models: List<ProviderInfo.ProviderModelInfo>)
        fun onError(message: String)
    }

    @JvmStatic
    fun fetch(apiHost: String?, apiPath: String?, apiKey: String?, callback: Callback) {
        EXECUTOR.execute {
            try {
                val base = ApiUtils.toModelsBaseUrl(apiHost, apiPath)
                var requestUrl: String? = buildModelsUrl(base, null)
                var nextAfter: String? = null
                val fetchedRequestKeys = HashSet<String>()
                val seenModelIds = HashSet<String>()
                val models = ArrayList<ProviderInfo.ProviderModelInfo>()

                var i = 0
                while (i < MAX_MODEL_PAGE_FETCH && requestUrl != null) {
                    if (!fetchedRequestKeys.add(requestUrl)) {
                        break
                    }
                    val req = Request.Builder()
                        .url(requestUrl)
                        .addHeader("Authorization", "Bearer " + (apiKey ?: ""))
                        .get()
                        .build()
                    var result: PageResult? = null
                    CLIENT.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful || resp.body == null) {
                            val err = resp.body?.string() ?: "HTTP ${resp.code}"
                            postError(callback, err)
                            return@execute
                        }
                        result = parsePage(resp.body!!.string())
                    }
                    val pageResult = result ?: break
                    for (id in pageResult.modelIds) {
                        if (seenModelIds.add(id)) {
                            models.add(ProviderInfo.ProviderModelInfo(id))
                        }
                    }

                    if (!pageResult.nextUrl.isNullOrEmpty()) {
                        requestUrl = pageResult.nextUrl
                        i++
                        continue
                    }

                    nextAfter = pageResult.nextCursor
                    if ((nextAfter.isNullOrEmpty()) && pageResult.hasMore) {
                        nextAfter = pageResult.lastId
                    }
                    if (nextAfter.isNullOrEmpty()) {
                        requestUrl = null
                    } else {
                        requestUrl = buildModelsUrl(base, nextAfter)
                    }
                    i++
                }
                val finalList: List<ProviderInfo.ProviderModelInfo> = models
                MAIN.post { callback.onSuccess(finalList) }
            } catch (e: IOException) {
                postError(callback, e.message)
            } catch (e: Exception) {
                postError(callback, e.message)
            }
        }
    }

    private fun postError(callback: Callback, msg: String?) {
        MAIN.post { callback.onError(msg ?: "Unknown error") }
    }

    private fun buildModelsUrl(base: String, after: String?): String {
        val url = base + (if (base.endsWith("/")) "" else "/") + "models"
        val parsed = url.toHttpUrlOrNull() ?: return url
        val builder = parsed.newBuilder()
        if (!after.isNullOrEmpty()) {
            builder.addQueryParameter("after", after)
        }
        return builder.build().toString()
    }

    private fun parsePage(body: String): PageResult {
        val json = GSON.fromJson(body, JsonObject::class.java)
        val page = PageResult()
        val data: JsonArray? = if (json != null && json.has("data")) json.getAsJsonArray("data") else null
        if (data != null) {
            for (i in 0 until data.size()) {
                val item = data.get(i).asJsonObject
                val id = getString(item, "id")
                if (!id.isNullOrEmpty()) {
                    page.modelIds.add(id!!)
                    page.lastId = id
                }
            }
        }
        if (json != null) {
            page.hasMore = getBoolean(json, "has_more", "hasMore")
            page.nextCursor = getString(json, "next_cursor", "nextCursor", "after", "cursor")
            val next = getString(json, "next")
            if (next != null && (next.startsWith("http://") || next.startsWith("https://"))) {
                page.nextUrl = next
            } else if (page.nextCursor.isNullOrEmpty() && !next.isNullOrEmpty()) {
                page.nextCursor = next
            }
        }
        return page
    }

    private fun getString(obj: JsonObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) {
            if (!obj.has(key)) continue
            val element: JsonElement = obj.get(key)
            if (element.isJsonNull) continue
            val value = element.asString
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun getBoolean(obj: JsonObject?, vararg keys: String): Boolean {
        if (obj == null) return false
        for (key in keys) {
            if (!obj.has(key)) continue
            val element: JsonElement = obj.get(key)
            if (element.isJsonNull) continue
            try {
                return element.asBoolean
            } catch (ignored: Exception) {
            }
        }
        return false
    }

    private class PageResult {
        val modelIds = ArrayList<String>()
        var nextCursor: String? = null
        var nextUrl: String? = null
        var lastId: String? = null
        var hasMore = false
    }
}
