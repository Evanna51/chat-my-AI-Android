package com.example.aichat

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class SessionOutlineStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(sessionId: String?): List<SessionOutlineItem> {
        if (sessionId?.trim().isNullOrEmpty()) return ArrayList()
        val raw = prefs.getString(KEY_PREFIX + sessionId, "[]")
        var list: List<SessionOutlineItem>? = GSON.fromJson(raw, LIST_TYPE)
        if (list == null) list = ArrayList()
        return list.sortedBy { it?.createdAt ?: 0L }
    }

    fun saveAll(sessionId: String?, items: List<SessionOutlineItem>?) {
        if (sessionId?.trim().isNullOrEmpty()) return
        val safe = items ?: ArrayList()
        prefs.edit().putString(KEY_PREFIX + sessionId, GSON.toJson(safe)).apply()
    }

    fun add(sessionId: String?, type: String?, title: String?, content: String?): SessionOutlineItem {
        val list = getAll(sessionId).toMutableList()
        val item = SessionOutlineItem()
        val now = System.currentTimeMillis()
        item.id = UUID.randomUUID().toString()
        item.type = normalizeType(type)
        item.title = title?.trim() ?: ""
        item.content = content?.trim() ?: ""
        item.createdAt = now
        item.updatedAt = now
        list.add(item)
        saveAll(sessionId, list)
        return item
    }

    fun update(sessionId: String?, updated: SessionOutlineItem?) {
        if (updated == null || updated.id == null) return
        val list = getAll(sessionId).toMutableList()
        for (one in list) {
            if (one != null && updated.id == one.id) {
                one.type = normalizeType(updated.type)
                one.title = updated.title?.trim() ?: ""
                one.content = updated.content?.trim() ?: ""
                one.updatedAt = System.currentTimeMillis()
                break
            }
        }
        saveAll(sessionId, list)
    }

    fun delete(sessionId: String?, itemId: String?) {
        if (itemId?.trim().isNullOrEmpty()) return
        val list = getAll(sessionId)
        val out = ArrayList<SessionOutlineItem>()
        for (one in list) {
            if (one == null || one.id == null || one.id == itemId) continue
            out.add(one)
        }
        saveAll(sessionId, out)
    }

    fun nextChapterIndex(sessionId: String?): Int {
        var max = 0
        val list = getAll(sessionId)
        for (one in list) {
            if (one == null) continue
            if ("chapter" != normalizeType(one.type)) continue
            val t = one.title ?: ""
            val idx = parseChapterIndex(t)
            if (idx > max) max = idx
        }
        return max + 1
    }

    fun normalizeType(type: String?): String {
        if (type == "chapter"
            || type == "material"
            || type == "task"
            || type == "world"
            || type == "knowledge") {
            return type
        }
        return "chapter"
    }

    private fun parseChapterIndex(title: String?): Int {
        if (title?.trim().isNullOrEmpty()) return 0
        val t = title!!.trim()
        val digits = t.replace(Regex("[^0-9]"), "")
        if (digits.isEmpty()) return 0
        return try {
            Integer.parseInt(digits)
        } catch (ignored: Exception) {
            0
        }
    }

    companion object {
        private const val PREFS = "aichat_session_outlines"
        private const val KEY_PREFIX = "outlines_"
        private val GSON = Gson()
        private val LIST_TYPE = object : TypeToken<List<SessionOutlineItem>>() {}.type
    }
}
