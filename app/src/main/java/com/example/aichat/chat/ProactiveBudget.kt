package com.example.aichat.chat

import android.content.Context
import com.example.aichat.ConfiguredModelPicker
import com.example.aichat.ProviderManager
import com.example.aichat.SessionChatOptionsStore
import java.util.Calendar
import java.util.Locale

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

    /**
     * 单沉默期内, 由会话当前 (云端) 模型驱动的 follow-up 上限.
     * 超过这条会尝试切换到本地 fallback 模型继续 (节省成本); 若没有
     * 本地 provider 则就此停链.
     */
    const val CLOUD_FOLLOWUP_CHAIN_MAX = 10

    /**
     * 单沉默期内 follow-up 链的硬上限 (含云端 + 本地). 200 是出于
     * "再聊不动也得停" 的现实考虑; AI 若输出 META.autoStop=true 可随时
     * 提前结束.
     */
    const val HARD_FOLLOWUP_CHAIN_MAX = 200

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

    /**
     * 找一个"本地" provider 当 fallback. 优先级:
     *   1. 名字含 lmstudio / ollama / llama / koboldcpp / lm-studio
     *   2. apiHost 是 localhost / 127.0.0.1 / 192.168.* / 10.* / *.local
     * 返回 ConfiguredModel 的 storageKey ("providerId:modelId"); 没有就返回 null.
     *
     * 优先选第一个能用 (有 host + 至少一个 model). 用户可以通过给某个 provider
     * 起名 "lmstudio" 强制识别.
     */
    fun findLocalFallbackModelKey(context: Context): String? {
        val ctx = context.applicationContext
        val configured = try { ConfiguredModelPicker.getConfiguredModels(ctx) }
            catch (_: Exception) { return null }
        if (configured.isEmpty()) return null
        val pm = ProviderManager(ctx)
        // Pass 1: name match
        for (opt in configured) {
            val pid = opt.providerId ?: continue
            val provider = pm.getProvider(pid) ?: continue
            val name = provider.name.lowercase(Locale.ROOT)
            if (LOCAL_NAME_HINTS.any { name.contains(it) }) {
                return opt.getStorageKey()
            }
        }
        // Pass 2: host match
        for (opt in configured) {
            val pid = opt.providerId ?: continue
            val provider = pm.getProvider(pid) ?: continue
            if (isLocalHost(provider.apiHost)) {
                return opt.getStorageKey()
            }
        }
        return null
    }

    private val LOCAL_NAME_HINTS = listOf("lmstudio", "lm studio", "lm-studio", "ollama", "llama.cpp", "koboldcpp")

    private fun isLocalHost(apiHost: String?): Boolean {
        val h = (apiHost ?: "").lowercase(Locale.ROOT)
        if (h.isEmpty()) return false
        if (h.contains("localhost") || h.contains("127.0.0.1")) return true
        if (h.contains(".local")) return true
        // RFC1918 internal nets — naive substring check; 用户应该懂得不在公网用 192.168.
        if (h.contains("192.168.")) return true
        if (h.contains("//10.") || h.contains(":10.")) return true
        // 172.16-31.x.x
        for (i in 16..31) if (h.contains("172.$i.")) return true
        return false
    }
}
