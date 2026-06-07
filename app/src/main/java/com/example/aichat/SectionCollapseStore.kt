package com.example.aichat

import android.content.Context

/**
 * Per-session 持久化 outline 页折叠了哪些 section。
 *
 * 存 SP 里, key = `outline_collapsed_<sessionId>`, value = 用 `|` 分隔的 type 字符串。
 * 数据量极小, 不上 Room。
 */
class SectionCollapseStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(sessionId: String?): Set<String> {
        if (sessionId.isNullOrBlank()) return emptySet()
        val raw = prefs.getString(KEY_PREFIX + sessionId, "") ?: ""
        if (raw.isEmpty()) return emptySet()
        return raw.split('|').filter { it.isNotBlank() }.toSet()
    }

    fun save(sessionId: String?, collapsed: Set<String>) {
        if (sessionId.isNullOrBlank()) return
        val joined = collapsed.filter { it.isNotBlank() }.joinToString("|")
        prefs.edit().putString(KEY_PREFIX + sessionId, joined).apply()
    }

    companion object {
        private const val PREFS = "aichat_outline_section_collapse"
        private const val KEY_PREFIX = "outline_collapsed_"
    }
}
