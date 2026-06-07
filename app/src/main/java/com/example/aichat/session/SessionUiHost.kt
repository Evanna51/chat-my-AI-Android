package com.example.aichat.session

import com.example.aichat.Message

/**
 * Activity 暴露给 [SessionModeStrategy] 的最小能力面。
 *
 * 反向依赖原则：strategy 不知道 Activity 是什么（不 import Activity 类、
 * 不持 View 引用）；只通过这个接口请求 Activity 执行少量动作。
 *
 * 目前面很小（2 个成员）。后续需要新动作就加成员，不要给 strategy
 * 暴露整个 Activity。
 */
interface SessionUiHost {
    /** 当前会话绑定的 assistantId，可能为 null（无助手）。*/
    val assistantId: String?

    /** Writer 模式：把指定消息总结成大纲条目。仅 WriterModeStrategy.onOutlineAction 会调。*/
    fun summarizeMessageToOutline(message: Message)
}
