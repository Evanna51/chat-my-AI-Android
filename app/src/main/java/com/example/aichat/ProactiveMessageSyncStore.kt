package com.example.aichat

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ProactiveMessageSyncStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun getLastSince(): String {
        return safeTrim(prefs.getString(KEY_LAST_SINCE, ""))
    }

    @Synchronized
    fun setLastSince(since: String?) {
        prefs.edit().putString(KEY_LAST_SINCE, safeTrim(since)).apply()
    }

    @Synchronized
    fun isRecentlyProcessed(messageId: String?): Boolean {
        val id = safeTrim(messageId)
        if (id.isEmpty()) return false
        val map = getProcessedMap().toMutableMap()
        pruneExpired(map, System.currentTimeMillis())
        saveProcessedMap(map)
        return map.containsKey(id)
    }

    @Synchronized
    fun markProcessed(messageId: String?) {
        val id = safeTrim(messageId)
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        val map = getProcessedMap().toMutableMap()
        pruneExpired(map, now)
        map[id] = now
        if (map.size > MAX_TRACKED_IDS) {
            dropOldest(map)
        }
        saveProcessedMap(map)
    }

    private fun getProcessedMap(): Map<String, Long> {
        val json = prefs.getString(KEY_PROCESSED_IDS, "{}")
        val map: Map<String, Long>? = GSON.fromJson(json, MAP_TYPE)
        return map ?: HashMap()
    }

    private fun saveProcessedMap(map: Map<String, Long>) {
        prefs.edit().putString(KEY_PROCESSED_IDS, GSON.toJson(map)).apply()
    }

    private fun pruneExpired(map: MutableMap<String, Long>, now: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val one = it.next()
            if (one.key.trim().isEmpty()) {
                it.remove()
                continue
            }
            val ts = one.value
            if (now - ts > PROCESSED_TTL_MS) {
                it.remove()
            }
        }
    }

    private fun dropOldest(map: MutableMap<String, Long>) {
        var oldestKey: String? = null
        var oldest = Long.MAX_VALUE
        for ((key, value) in map) {
            val ts = value
            if (ts < oldest) {
                oldest = ts
                oldestKey = key
            }
        }
        if (oldestKey != null) map.remove(oldestKey)
    }

    private fun safeTrim(text: String?): String {
        return text?.trim() ?: ""
    }

    companion object {
        private const val PREFS = "aichat_proactive_sync"
        private const val KEY_LAST_SINCE = "last_since"
        private const val KEY_PROCESSED_IDS = "processed_ids"
        private const val PROCESSED_TTL_MS = 24L * 60L * 60L * 1000L
        private const val MAX_TRACKED_IDS = 500
        private val GSON = Gson()
        private val MAP_TYPE = object : TypeToken<Map<String, Long>>() {}.type
    }
}
