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
 * Caches `POST /api/character/context` payloads per assistantId so the chat
 * dispatch path can read renderedSlots / coreFacts / coreMemories synchronously
 * when building system prompt.
 *
 * - 内存级 cache (per-process). 不持久化 — 进程重启会重新拉.
 * - TTL: 同一 assistantId 距上次成功 fetch [TTL_MS] 内 no-op, 超过就 refresh.
 * - 失败容错: 网络错误时保留旧 cache, 不阻塞 chat.
 *
 * `relationshipState` 仍走现有 [RelationshipStateStore] (Room 持久化, 跨进程 ok).
 */
class CharacterBootstrapStore private constructor(private val appContext: Context) {

    /** Single-line in-memory cache row. */
    data class Cache(
        val assistantId: String,
        /** character/context renderedSlots：role / character / background / constraints / toolProtocol. */
        val renderedSlots: ChatRenderedSlots? = null,
        val coreMemories: List<CoreMemory>,
        val coreFacts: List<CoreFact>,
        val fetchedAtMs: Long,
        val fetchedDayKey: Int,
        /** 原始 JSON 响应, 供"查看角色信息"页面展示. */
        val rawJson: String = "",
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
     * Fire-and-forget refresh. 距上次成功 fetch 超过 [TTL_MS] 才发请求, 否则 no-op.
     * 调用方 (e.g. ChatSessionActivity.onResume) 不需要等 — chat dispatch 时
     * [getCached] 直接读, 没有也只是没注入 coreMemories/coreFacts, 不影响主流程.
     */
    fun refreshIfStale(assistantId: String?) {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return
        val existing = cacheByAssistant[aid]
        if (existing != null && (System.currentTimeMillis() - existing.fetchedAtMs) < TTL_MS) return
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

        // 调 /api/character/context（admin/debug 端点 — 拿 V_NEW_LEAN mergedSystem
        // + 7 层认知态 + slots，不带本轮 user 上下文）。chat hot path 每轮发消息时
        // 走 chatContext 拿带 facts/narrative 的当轮上下文 — 那是 ChatViewModel 的事。
        val raw = try {
            api.characterContext(aid)
        } catch (e: Exception) {
            Log.w(TAG, "character/context failed for $aid: ${e.message}")
            return null
        }
        val cache = parse(aid, raw) ?: return null
        cacheByAssistant[aid] = cache
        // Fan out relationshipState into existing store
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
                Log.w(TAG, "character/context returned ok=false: $raw")
                return null
            }
            Cache(
                assistantId = aid,
                renderedSlots = parseRenderedSlots(root),
                coreMemories = parseCoreMemories(root.get("coreMemories")),
                coreFacts = parseCoreFacts(root.get("coreFacts")),
                fetchedAtMs = System.currentTimeMillis(),
                fetchedDayKey = todayKey(),
                rawJson = raw,
            )
        } catch (e: Exception) {
            Log.w(TAG, "character/context parse failed", e)
            null
        }
    }

    private fun parseRenderedSlots(root: JsonObject): ChatRenderedSlots? {
        val el = root.get("renderedSlots") ?: return null
        if (el.isJsonNull || !el.isJsonObject) return null
        val obj = el.asJsonObject
        return ChatRenderedSlots(
            role        = readStr(obj, "role").takeIf { it.isNotEmpty() },
            character   = readStr(obj, "character").takeIf { it.isNotEmpty() },
            background  = readStr(obj, "background").takeIf { it.isNotEmpty() },
            constraints = readStr(obj, "constraints").takeIf { it.isNotEmpty() },
            toolProtocol = readStr(obj, "tool_protocol").takeIf { it.isNotEmpty() },
        )
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
        /** Refresh TTL — same assistantId 间隔小于这个就走缓存. */
        private const val TTL_MS = 10L * 60 * 1000

        @Volatile private var instance: CharacterBootstrapStore? = null

        fun getInstance(context: Context): CharacterBootstrapStore =
            instance ?: synchronized(this) {
                instance ?: CharacterBootstrapStore(context.applicationContext).also { instance = it }
            }
    }
}
