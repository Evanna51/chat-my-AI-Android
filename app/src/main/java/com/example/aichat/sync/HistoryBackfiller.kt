package com.example.aichat.sync

import android.content.Context
import com.example.aichat.AppDatabase
import com.example.aichat.SessionAssistantBindingStore
import com.example.aichat.SessionChatOptionsStore

/**
 * 一次性补齐历史 user/assistant 消息的 turnId / assistantId, 让它们进入待同步队列.
 *
 * 规则:
 *   - 已绑 Assistant 的 session → 用绑定的 assistantId
 *   - 未绑 session → 用 [DefaultAssistantId] (modelKey + 季度) 生成 fallback
 *   - sessionId 为空的孤儿行 → 跳过
 *
 * 多次调用幂等: 已 stamp 过的行 (turnId 非空) 不会被再次处理.
 */
object HistoryBackfiller {

    data class Result(val total: Int, val stamped: Int, val skipped: Int)

    fun backfill(context: Context): Result {
        val dao = AppDatabase.getInstance(context).messageDao()
        val bindingStore = SessionAssistantBindingStore(context)
        val optionsStore = SessionChatOptionsStore(context)
        val rows = dao.unstampedMessages()

        val sessionAssistantCache = HashMap<String, String>()
        val sessionModelKeyCache = HashMap<String, String>()

        var stamped = 0
        var skipped = 0
        for (m in rows) {
            val sid = m.sessionId
            if (sid.isEmpty()) { skipped++; continue }

            val explicit = sessionAssistantCache.getOrPut(sid) {
                bindingStore.getAssistantId(sid)
            }
            val aid = if (explicit.isNotEmpty()) {
                explicit
            } else {
                val modelKey = sessionModelKeyCache.getOrPut(sid) {
                    optionsStore.get(sid).modelKey
                }
                DefaultAssistantId.forModelKey(modelKey, m.createdAt)
            }

            val newTurnId = UuidV7.next(if (m.createdAt > 0) m.createdAt else System.currentTimeMillis())
            dao.stampSyncFields(m.id, newTurnId, aid)
            stamped++
        }
        return Result(rows.size, stamped, skipped)
    }
}
