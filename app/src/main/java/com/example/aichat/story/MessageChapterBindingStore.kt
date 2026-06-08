package com.example.aichat.story

import android.content.Context

/**
 * 持久化「assistant 消息 → 章节大纲条目」的显式绑定关系。
 *
 * 用途：用户手动选择某条 assistant 消息对应哪章，
 * [estimateCurrentChapterCount] 优先使用此绑定，而非靠文字匹配推断。
 *
 * 存储：SharedPreferences，key = "${sessionId}_${messageId}"，value = chapterId。
 */
class MessageChapterBindingStore(context: Context) {

    private val prefs = context.getSharedPreferences("msg_chapter_bindings", Context.MODE_PRIVATE)

    fun bind(sessionId: String, messageId: Long, chapterId: String) {
        prefs.edit().putString(key(sessionId, messageId), chapterId).apply()
    }

    fun getChapterId(sessionId: String, messageId: Long): String? =
        prefs.getString(key(sessionId, messageId), null)

    /** 清除某章节的所有绑定（重新绑定时清理旧记录）。 */
    fun clearForChapter(sessionId: String, chapterId: String) {
        val prefix = "${sessionId}_"
        val toRemove = prefs.all.entries
            .filter { (k, v) -> k.startsWith(prefix) && v == chapterId }
            .map { it.key }
        if (toRemove.isNotEmpty()) {
            prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
        }
    }

    private fun key(sessionId: String, messageId: Long) = "${sessionId}_$messageId"
}
