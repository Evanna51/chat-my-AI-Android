package com.example.aichat.session

import com.example.aichat.MyAssistant

/**
 * 助手会话模式。原来散落各处的 `MyAssistant.type` 字符串比较都收口到这里。
 *
 * raw 必须与持久化字符串保持一致（Room MyAssistantEntity.type、
 * SessionListAdapter.GROUP_WRITER 等都是这些值）。改 raw 会破坏现有数据。
 */
enum class SessionMode(val raw: String) {
    DEFAULT(""),
    CHARACTER("character"),
    WRITER("writer");

    companion object {
        fun from(assistantType: String?): SessionMode {
            val key = assistantType?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.raw == key } ?: DEFAULT
        }
    }
}

fun MyAssistant?.mode(): SessionMode = SessionMode.from(this?.type)
