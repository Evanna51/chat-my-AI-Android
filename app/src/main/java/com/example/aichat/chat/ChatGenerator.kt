package com.example.aichat.chat

import com.example.aichat.Message
import com.example.aichat.SessionChatOptions
import com.example.aichat.sync.ToolBridge

/**
 * 聊天生成器的统一接口 —— 让上层（ChatViewModel / 未来的 SessionModeStrategy）
 * 与具体协议（OpenAI 兼容 / 其它）解耦。
 *
 * R5 引入。当前唯一实现是 [com.example.aichat.ChatService]。
 *
 * 涵盖范围：
 *   - 用户消息流式分发（[chat]）
 *   - 会话起标题（[generateThreadTitle]）—— 模式无关
 *
 * 不涵盖：writer 模式专属生成（大纲 / 章纲 / 知情约束 / 单条总结）—— 它们走
 * `WriterOutlineService` / `WriterChapterPlanService` / `WriterVolumeService`。
 */
interface ChatGenerator {

    fun chat(
        history: List<Message>,
        userMessage: String,
        options: SessionChatOptions? = null,
        callback: ChatCallback,
        toolBridge: ToolBridge? = null,
    ): ChatHandle

    fun generateThreadTitle(firstUserMessage: String?, callback: ChatCallback)
}
