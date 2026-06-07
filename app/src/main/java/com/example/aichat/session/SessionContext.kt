package com.example.aichat.session

import com.example.aichat.Message
import com.example.aichat.MyAssistant
import com.example.aichat.SessionChatOptions

/**
 * 一次「策略钩子调用」用的不可变数据快照。
 *
 * Activity 在调 strategy 前用当前状态构造一个 ctx，传过去；strategy 全程
 * 只读这个 ctx，不再回查 Activity 状态。这样 strategy 是**纯函数**，单测
 * 时可以喂任意 ctx 而不需要 Activity / View / DB。
 */
data class SessionContext(
    val sessionId: String,
    val assistantId: String?,
    val assistant: MyAssistant?,
    val options: SessionChatOptions,
    /**
     * Writer 模式专用：当前 session 的写作大纲块文本（OutlinePromptBuilder.build 结果）。
     * 其他模式 Activity 构造时填空串。
     */
    val writerOutlineBlock: String = "",
    /**
     * Writer 模式专用：历史助手消息节选时的"前/中/后段"长度上限。
     * 对应 ChatSessionActivity 旧常量 WRITER_ASSISTANT_LAST_SEGMENT_CHARS。
     */
    val writerLastSegmentChars: Int = 1000,
    /**
     * Writer 模式专用：早期助手消息（不是最新一条）的节选阈值。
     * 对应 ChatSessionActivity 旧常量 WRITER_ASSISTANT_CONTEXT_EXCERPT_MAX_CHARS。
     */
    val writerEarlyExcerptMaxChars: Int = 500,
)
