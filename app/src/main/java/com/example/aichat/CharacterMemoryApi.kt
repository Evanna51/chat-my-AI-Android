package com.example.aichat

object CharacterMemoryApi {
    const val PATH_MEMORY_CONTEXT = "/api/tool/memory-context"
    const val PATH_REPORT_CHARACTER_PROFILE = "/api/assistant-profile/upsert"

    data class MemoryContextRequest(
        @JvmField var assistantId: String = "",
        @JvmField var sessionId: String = "",
        // Keep both keys for server compatibility:
        // some deployments expect `userInput`, others may still read `userMessage`.
        @JvmField var userInput: String = "",
        @JvmField var userMessage: String = ""
    )

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
