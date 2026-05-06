package com.example.aichat

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Cold-cache wrapper for [RelationshipStateEntity].
 *
 * Phase A3 contract:
 * - This store does NOT call the server itself. It's purely a sink for
 *   payloads that other components fetch (e.g. CharacterMemoryService when
 *   server starts dressing /api/tool/memory-context responses with
 *   `relationshipState`), and a source for prompt injection / UI display.
 * - Read paths must be safe to call from the chat dispatch path
 *   (synchronous Room read, single-row keyed by assistantId — fast enough).
 * - Write paths happen on background threads (server response handlers).
 *
 * When server eventually exposes a dedicated `/api/relationship/state`
 * endpoint, plug it in next to the existing CharacterMemoryService calls
 * and feed the returned JSON straight into [upsertFromServerJson].
 */
class RelationshipStateStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dao: RelationshipStateDao =
        AppDatabase.getInstance(appContext).relationshipStateDao()

    /** Read the cached relationship row for an assistant. Null if never fetched. */
    fun getCached(assistantId: String?): RelationshipStateEntity? {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return null
        return try {
            dao.getByAssistant(aid)
        } catch (e: Exception) {
            Log.w(TAG, "getCached failed for $aid", e)
            null
        }
    }

    /**
     * Parse a server-shaped relationship payload and upsert it for [assistantId].
     *
     * Tolerant parsing — missing fields fall back to safe defaults, malformed
     * JSON degrades to storing only [rawJson] so we still have something to
     * inspect later. Returns the persisted entity on success, null on hard
     * failure (assistantId blank).
     *
     * Recognized fields (loose schema, keep in sync with wi-chat-server):
     *   { closeness:int, trustLevel:string, sharedTopics:string[],
     *     lastEmotionalTone:string, lastInteractionAt:long_ms }
     */
    fun upsertFromServerJson(assistantId: String?, json: String?): RelationshipStateEntity? {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return null
        val raw = json?.trim().orEmpty()

        val entity = RelationshipStateEntity().apply {
            this.assistantId = aid
            this.rawJson = raw
            this.fetchedAt = System.currentTimeMillis()
        }

        if (raw.isNotEmpty()) {
            try {
                val obj = JsonParser().parse(raw)
                if (obj.isJsonObject) {
                    val o = obj.asJsonObject
                    entity.closeness = readInt(o, "closeness").coerceIn(0, 100)
                    entity.trustLevel = readString(o, "trustLevel")
                    entity.sharedTopicsJson = readStringArray(o, "sharedTopics")
                    entity.lastEmotionalTone = readString(o, "lastEmotionalTone")
                    entity.lastInteractionAt = readLong(o, "lastInteractionAt")
                }
            } catch (e: Exception) {
                // Keep rawJson around; structured fields stay at defaults.
                Log.w(TAG, "parse relationship payload failed, keeping raw only", e)
            }
        }

        return try {
            dao.upsert(entity)
            entity
        } catch (e: Exception) {
            Log.w(TAG, "upsert relationship failed for $aid", e)
            null
        }
    }

    /**
     * Build a short, model-readable hint summarizing the cached relationship
     * state. Returns null when no useful data — caller should NOT inject
     * anything in that case rather than padding the prompt with empty stubs.
     *
     * Keep concise: this gets prepended to system prompt on every request,
     * so verbosity directly costs tokens.
     */
    fun buildPromptHint(state: RelationshipStateEntity?): String? {
        if (state == null) return null
        val parts = mutableListOf<String>()
        if (state.closeness > 0) parts.add("亲密度 ${state.closeness}/100")
        if (state.trustLevel.isNotEmpty()) parts.add("信任 ${state.trustLevel}")
        val topics = parseStringArray(state.sharedTopicsJson)
        if (topics.isNotEmpty()) {
            parts.add("最近共同话题: " + topics.take(MAX_HINT_TOPICS).joinToString("、"))
        }
        if (state.lastEmotionalTone.isNotEmpty()) {
            parts.add("最近情绪基调: ${state.lastEmotionalTone}")
        }
        if (parts.isEmpty()) return null
        return "[与该用户的关系状态] " + parts.joinToString(" / ")
    }

    /** Convenience: combine [getCached] + [buildPromptHint] in one call. */
    fun buildPromptHintForAssistant(assistantId: String?): String? =
        buildPromptHint(getCached(assistantId))

    private fun readString(obj: JsonObject, key: String): String {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) "" else try { e.asString } catch (_: Exception) { "" }
    }

    private fun readInt(obj: JsonObject, key: String): Int {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) 0 else try { e.asInt } catch (_: Exception) { 0 }
    }

    private fun readLong(obj: JsonObject, key: String): Long {
        val e: JsonElement? = obj.get(key)
        return if (e == null || e.isJsonNull) 0L else try { e.asLong } catch (_: Exception) { 0L }
    }

    private fun readStringArray(obj: JsonObject, key: String): String {
        val e: JsonElement? = obj.get(key)
        if (e == null || e.isJsonNull || !e.isJsonArray) return "[]"
        val cleaned = JsonArray()
        for (one in e.asJsonArray) {
            if (one == null || one.isJsonNull) continue
            try {
                val s = one.asString
                if (s.isNotEmpty()) cleaned.add(s)
            } catch (_: Exception) {}
        }
        return cleaned.toString()
    }

    private fun parseStringArray(json: String): List<String> {
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JsonParser().parse(json)
            if (!arr.isJsonArray) return emptyList()
            arr.asJsonArray.mapNotNull {
                if (it == null || it.isJsonNull) null else try { it.asString } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val TAG = "RelationshipStateStore"
        private const val MAX_HINT_TOPICS = 3
    }
}
