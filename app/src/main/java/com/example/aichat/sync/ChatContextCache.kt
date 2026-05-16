package com.example.aichat.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内 cache：每个 assistantId 最近一次成功的 `POST /api/chat/context` 响应。
 *
 * 降级链：chat/context 成功 → 存入 cache；失败 → [get] 取最近有效缓存；
 * 缓存也过期/不存在 → 走 boot cache（character/context slots 拼接）。
 *
 * TTL = 15 分钟：context 含 attention_1h / narrative 等动态 slot，15min 内作为
 * 降级兜底可接受；超过则视为过期，不用。
 *
 * 不持久化 — 进程重启后空。
 */
object ChatContextCache {
    private const val TTL_MS = 15 * 60 * 1000L

    private data class Entry(val response: ChatContextResponse, val cachedAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun put(assistantId: String?, response: ChatContextResponse) {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return
        cache[aid] = Entry(response, System.currentTimeMillis())
    }

    /** 返回 TTL 内的缓存；过期或不存在返回 null。 */
    fun get(assistantId: String?): ChatContextResponse? {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return null
        val entry = cache[aid] ?: return null
        return if (System.currentTimeMillis() - entry.cachedAtMs <= TTL_MS) entry.response else null
    }
}
