package com.example.aichat.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内 cache：每个 assistantId 最近一次"实际发给 LLM 的 system prompt"快照。
 *
 * ChatViewModel 在每轮 doChatRequest 拼好 mergedSystemPrompt 后调 [record]；
 * CharacterInfoActivity 用 [get] 读出来给用户看真实下发的 prompt（而不是
 * boot cache 里的 V_NEW_LEAN mergedSystem 占位）。
 *
 * 不持久化 — 进程重启后空。Activity 在没有 snapshot 时显示提示文案。
 */
object EffectivePromptStore {
    data class Snapshot(
        val systemPrompt: String,
        /** "v3-path-b" / "v3-flat" / "fallback" */
        val source: String,
        val capturedAtMs: Long,
        /** 简短 router 决策概览；非 V3 path 时 null。 */
        val routerSummary: String? = null,
    )

    private val cache = ConcurrentHashMap<String, Snapshot>()

    fun record(assistantId: String?, snapshot: Snapshot) {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return
        cache[aid] = snapshot
    }

    fun get(assistantId: String?): Snapshot? {
        val aid = assistantId?.trim().orEmpty()
        if (aid.isEmpty()) return null
        return cache[aid]
    }
}
