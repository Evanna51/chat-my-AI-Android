package com.example.aichat.chat

/**
 * 一条工具调用相关消息的快照，由 [ChatCallback.onToolMessageRecorded] 派发。
 * 从 ChatService.ToolMessageRecord 提升到顶级（R5）。
 *
 * 字段语义：
 * - role: Message.ROLE_TOOL_CALL（assistant 发出 tool_calls 的包装行）或
 *   Message.ROLE_TOOL_RESULT（每次工具执行后的结果行）
 * - content: assistant tool_call 时为空；tool_result 时为工具返回的 JSON
 * - toolCallsJson: tool_call 行存 OpenAI 协议的 tool_calls 数组（JSON 字符串）
 * - toolCallId / toolName: tool_result 行用，标识被哪一次调用
 * - createdAt: 行写入时间戳（毫秒）
 */
data class ToolMessageRecord(
    @JvmField val role: Int,
    @JvmField val content: String,
    @JvmField val toolCallsJson: String,
    @JvmField val toolCallId: String,
    @JvmField val toolName: String,
    @JvmField val createdAt: Long,
)
