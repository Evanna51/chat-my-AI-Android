package com.example.aichat.chat

/**
 * 一次聊天请求的句柄，调用方持有它以取消 / 查询取消状态。
 *
 * 从 ChatService.ChatHandle 提升到顶级（R5）。
 */
interface ChatHandle {
    fun cancel()
    fun isCancelled(): Boolean
}
