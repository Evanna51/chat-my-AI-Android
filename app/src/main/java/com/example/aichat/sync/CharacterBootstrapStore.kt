package com.example.aichat.sync

import android.content.Context
import android.util.Log
import com.example.aichat.RelationshipStateStore
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Caches `GET /api/character/bootstrap` payloads per assistantId so the chat
 * dispatch path can read coreMemories / coreFacts synchronously when building
 * system prompt.
 *
 * - 内存级 cache (per-process). 不持久化 — 进程重启会重新拉.
 * - 跨自然日 (本地时区) TTL: 同一 assistantId 当天只 fetch 一次.
 * - 失败容错: 网络错误时保留旧 cache, 不阻塞 chat.
 *
 * `relationshipState` 仍走现有 [RelationshipStateStore] (Room 持久化, 跨进程 ok).
 * 这里只缓存 bootstrap 特有的 coreMemories / coreFacts.
 */
class CharacterBootstrapStore private constructor(private val appContext: Context) {

    /** Single-line in-memory cache row. */
    data class Cache(
        val assistantId: String,
        val coreMemories: List<CoreMemory>,
        val coreFacts: List<CoreFact>,
        val fetchedAtMs: Long,
        val fetchedDayKey: Int,
    )

    data class CoreMemory(
        val id: String,
        val content: String,
        val memoryType: String,
        val category: String,
    )

    data class CoreFact(
        val factKey: String,
        val factValue: String,
        val score: Double,
    )

    private val cacheByAssistant = ConcurrentHashMap<String, Cache>()
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Fire-and-forget refresh. 跨自然日就 fetch, 同日命中就 no-op.
     * 调用方 (e.g. ChatSessionActivity.onResume) 不需要等 — chat dispatch 时
     * [getCached] 直接读, 没有也只是没注入 coreMemories/coreFacts, 不影响主流程.
     */
    fun refreshIfStale(assistantId: String?) {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return
        val today = todayKey()
        val existing = cacheByAssistant[aid]
        if (existing != null && existing.fetchedDayKey == today) return
        executor.execute { doRefresh(aid) }
    }

    /** Force refresh ignoring TTL. Returns null on failure. */
    fun refreshNow(assistantId: String?): Cache? {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return null
        return doRefresh(aid)
    }

    fun getCached(assistantId: String?): Cache? {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return null
        return cacheByAssistant[aid]
    }

    private fun doRefresh(aid: String): Cache? {
        val cfg = RemoteSyncConfigStore(appContext)
        if (!cfg.isEnabled() || cfg.getBaseUrl().isEmpty()) return null
        val api = ChatServerApi(cfg.getBaseUrl(), cfg.getApiKey())
        val raw = try {
            api.characterBootstrap(aid)
        } catch (e: Exception) {
            Log.w(TAG, "bootstrap fetch failed for $aid: ${e.message}")
            return null
        }
        val cache = parse(aid, raw) ?: return null
        cacheByAssistant[aid] = cache
        // Fan out relationshipState into existing store (already used by prompt path).
        try {
            extractRelationshipJson(raw)?.let { rsJson ->
                RelationshipStateStore(appContext).upsertFromServerJson(aid, rsJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "relationshipState extract failed", e)
        }
        return cache
    }

    private fun parse(aid: String, raw: String): Cache? {
        if (raw.isBlank()) return null
        return try {
            val root = JsonParser().parse(raw).asJsonObject
            if (!readBool(root, "ok", true)) {
                Log.w(TAG, "bootstrap returned ok=false: $raw")
                return null
            }
            Cache(
                assistantId = aid,
                coreMemories = parseCoreMemories(root.get("coreMemories")),
                coreFacts = parseCoreFacts(root.get("coreFacts")),
                fetchedAtMs = System.currentTimeMillis(),
                fetchedDayKey = todayKey(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "bootstrap parse failed", e)
            null
        }
    }

    private fun parseCoreMemories(el: JsonElement?): List<CoreMemory> {
        if (el == null || el.isJsonNull || !el.isJsonArray) return emptyList()
        val out = ArrayList<CoreMemory>()
        for (one in el.asJsonArray) {
            if (one == null || one.isJsonNull || !one.isJsonObject) continue
            val o = one.asJsonObject
            val content = readStr(o, "content")
            if (content.isEmpty()) continue
            out.add(CoreMemory(
                id = readStr(o, "id"),
                content = content,
                memoryType = readStr(o, "memoryType"),
                category = readStr(o, "category"),
            ))
        }
        return out
    }

    private fun parseCoreFacts(el: JsonElement?): List<CoreFact> {
        if (el == null || el.isJsonNull || !el.isJsonArray) return emptyList()
        val out = ArrayList<CoreFact>()
        for (one in el.asJsonArray) {
            if (one == null || one.isJsonNull || !one.isJsonObject) continue
            val o = one.asJsonObject
            val k = readStr(o, "factKey")
            val v = readStr(o, "factValue")
            if (k.isEmpty() || v.isEmpty()) continue
            out.add(CoreFact(
                factKey = k,
                factValue = v,
                score = readDouble(o, "score"),
            ))
        }
        return out
    }

    private fun extractRelationshipJson(raw: String): String? {
        return try {
            val root = JsonParser().parse(raw).asJsonObject
            val rs = root.get("relationshipState")
            if (rs == null || rs.isJsonNull || !rs.isJsonObject) null else rs.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun readStr(obj: JsonObject, key: String): String {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) "" else try { e.asString } catch (_: Exception) { "" }
    }

    private fun readDouble(obj: JsonObject, key: String): Double {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) 0.0 else try { e.asDouble } catch (_: Exception) { 0.0 }
    }

    private fun readBool(obj: JsonObject, key: String, default: Boolean): Boolean {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) default else try { e.asBoolean } catch (_: Exception) { default }
    }

    private fun todayKey(): Int {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        return cal.get(Calendar.YEAR) * 10000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }

    companion object {
        private const val TAG = "CharacterBootstrapStore"

        @Volatile private var instance: CharacterBootstrapStore? = null

        fun getInstance(context: Context): CharacterBootstrapStore =
            instance ?: synchronized(this) {
                instance ?: CharacterBootstrapStore(context.applicationContext).also { instance = it }
            }
    }
}
