package com.example.aichat.chat

import android.content.Context
import com.example.aichat.SessionChatOptionsStore
import java.util.Calendar

/**
 * Per-session daily budget gating for 自动对话.
 *
 * 共用入口供 [ProactiveChatPlanner] (in-process Handler 路径) 和
 * [com.example.aichat.proactive.ProactiveFollowUpWorker] (后台 WorkManager
 * 路径) 调用. 用 process-wide lock 保证写计数器没有竞态; 锁只保护短临界区,
 * worker 与 UI 不会真冲突.
 */
object ProactiveBudget {

    /** 默认每日上限. SessionChatOptions.proactiveDailyBudget = 0 时回退到此. */
    const val DEFAULT_DAILY_BUDGET = 60

    /** 用户允许配置的范围 (UI 校验). */
    const val MIN_DAILY_BUDGET = 1
    const val MAX_DAILY_BUDGET = 200

    private val LOCK = Any()

    /**
     * 尝试消费一次配额. 返回 true 表示可以发, 同时 counter +1; false 表示已耗尽 / 已关闭.
     *
     * 必须在工作线程调用 (会读写 SQLite). 跨天会自动重置 counter.
     */
    fun consumeIfAllowed(context: Context, sessionId: String): Boolean {
        if (sessionId.isEmpty()) return false
        val store = SessionChatOptionsStore(context.applicationContext)
        synchronized(LOCK) {
            val opts = store.get(sessionId)
            if (!opts.autoChatEnabled) return false
            val today = todayStamp()
            val limit = effectiveLimit(opts.proactiveDailyBudget)
            val current = if (opts.proactiveResetDate != today) 0 else opts.proactiveCountToday
            if (current >= limit) return false
            opts.proactiveCountToday = current + 1
            opts.proactiveResetDate = today
            store.save(sessionId, opts)
            return true
        }
    }

    fun effectiveLimit(stored: Int): Int {
        if (stored <= 0) return DEFAULT_DAILY_BUDGET
        return stored.coerceIn(MIN_DAILY_BUDGET, MAX_DAILY_BUDGET)
    }

    fun todayStamp(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 10000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }
}
