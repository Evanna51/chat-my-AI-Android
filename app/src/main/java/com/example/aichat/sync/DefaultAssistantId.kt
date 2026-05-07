package com.example.aichat.sync

import java.util.Calendar
import java.util.TimeZone

/**
 * 给"未绑定 Assistant 的会话"生成 fallback assistantId, 让它们也能进入远程同步.
 * 格式: `default-{providerId}-{YYYY}Q{1-4}`, e.g. `default-openai-2026Q2`.
 *
 * 按季度 + provider 分桶, 一年最多 4 × N 个 (N = 用过的 provider 数).
 */
object DefaultAssistantId {

    fun forModelKey(modelKey: String?, createdAt: Long): String {
        val provider = providerFromModelKey(modelKey)
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = if (createdAt > 0) createdAt else System.currentTimeMillis()
        val year = cal.get(Calendar.YEAR)
        val q = cal.get(Calendar.MONTH) / 3 + 1
        return "default-$provider-${year}Q$q"
    }

    private fun providerFromModelKey(modelKey: String?): String {
        val k = modelKey?.trim().orEmpty()
        if (k.isEmpty()) return "unknown"
        val i = k.indexOf(':')
        return if (i > 0) k.substring(0, i) else k
    }
}
