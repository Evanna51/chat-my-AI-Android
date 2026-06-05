package com.example.aichat.chat

/**
 * 聊天请求的回调集合。从 ChatService.ChatCallback 提升到顶级（R5），
 * 所有方法都有默认空实现，调用方只需 override 关心的几个。
 *
 * 调用顺序约定：
 *   - 流式请求：多次 onPartial / onReasoning → 可选 onToolCallStart /
 *     onToolMessageRecorded（工具循环） → onProactiveMeta → onSuccess
 *   - 失败：onError（不再调用 onSuccess）
 *   - 主动取消：onCancelled（不再调用 onError/onSuccess）
 */
interface ChatCallback {
    fun onSuccess(content: String)
    fun onError(message: String)
    fun onCancelled() {}
    fun onPartial(delta: String) {}
    fun onReasoning(reasoning: String) {}
    fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int, elapsedMs: Long) {}

    /**
     * Fired when the model emits a tool_call and the client is about to
     * invoke it. UI can show a "calling tool" indicator. Followed by either
     * onPartial/onReasoning (next round) or onError (tool loop aborted).
     */
    fun onToolCallStart(toolName: String) {}

    /**
     * Fired once for each persistable tool round message: first the
     * assistant(tool_calls) wrapper (role=ROLE_TOOL_CALL), then one row per
     * executed tool (role=ROLE_TOOL_RESULT). Consumers should write these
     * to the local message log so chat history is a faithful audit trail
     * of every LLM round.
     *
     * Contract: rows persisted via this callback MUST NOT be pushed to
     * the remote sync server — they're a local-only audit trail.
     * (Server schema since 2026-Q1 *does* accept role=tool_call /
     * tool_result, but we intentionally keep tool rounds local to limit
     * upload volume.) Implementations should leave `assistantId` empty
     * so SyncQueueDrainer's `assistantId != ''` filter skips them.
     * `turnId` is auto-assigned a non-empty UuidV7 by Message's
     * constructor — leave it alone; future cross-end delete sync may
     * rely on it.
     */
    fun onToolMessageRecorded(record: ToolMessageRecord) {}

    /**
     * Fired once per streaming chat turn, immediately before [onSuccess], when
     * 自动对话 META 协议在模型回复尾部被识别 (或缺席). [meta] 为 null 表示模型没发或解析失败,
     * 上层应回退到普通显示. 回调和 onSuccess 在同一线程顺序触发.
     */
    fun onProactiveMeta(meta: ProactiveMeta?) {}
}
