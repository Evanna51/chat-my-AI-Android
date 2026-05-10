package com.example.aichat

object CharacterMemoryApi {
    // Phase 2: /api/tool/memory-context 已删除；客户端走 /api/chat/context（合并 character/context + memory-context）
    const val PATH_CHAT_CONTEXT = "/api/chat/context"
    const val PATH_REPORT_CHARACTER_PROFILE = "/api/assistant-profile/upsert"

    data class CharacterProfileRequest(
        @JvmField var assistantId: String = "",
        @JvmField var characterName: String = "",
        @JvmField var characterBackground: String = "",
        @JvmField var allowAutoLife: Boolean = false,
        @JvmField var allowProactiveMessage: Boolean = false
    )

    data class MemoryContextResponse(
        @JvmField var ok: Boolean = false,
        @JvmField var shouldUseMemory: Boolean = false,
        @JvmField var reason: String = "",
        @JvmField var memoryLines: MutableList<String> = mutableListOf(),
        @JvmField var memoryGuidance: String = ""
    )
}
