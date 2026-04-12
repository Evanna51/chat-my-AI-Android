package com.example.aichat

import com.google.gson.JsonObject

object CharacterMemoryApi {
    const val PATH_REPORT_INTERACTION = "/api/report-interaction"
    const val PATH_MEMORY_CONTEXT = "/api/tool/memory-context"
    const val PATH_REPORT_CHARACTER_PROFILE = "/api/assistant-profile/upsert"
    const val PATH_PULL_MESSAGES = "/api/pull-messages"
    const val PATH_ACK_MESSAGE = "/api/ack-message"
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    data class ReportInteractionRequest(
        @JvmField var assistantId: String = "",
        @JvmField var sessionId: String = "",
        @JvmField var role: String = "",
        @JvmField var content: String = ""
    )

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

    data class PullMessagesResponse(
        @JvmField var ok: Boolean = false,
        @JvmField var userId: String = "",
        @JvmField var since: String = "",
        @JvmField var count: Int = 0,
        @JvmField var messages: MutableList<PulledMessage> = mutableListOf(),
        @JvmField var now: String = ""
    )

    data class PulledMessage(
        @JvmField var id: String = "",
        @JvmField var assistantId: String = "",
        @JvmField var sessionId: String = "",
        @JvmField var messageType: String = "",
        @JvmField var title: String = "",
        @JvmField var body: String = "",
        @JvmField var payload: JsonObject = JsonObject(),
        @JvmField var createdAt: String = "",
        @JvmField var availableAt: String = "",
        @JvmField var expiresAt: String = "",
        @JvmField var pullCount: Int = 0
    )

    data class AckMessageRequest(
        @JvmField var messageId: String = "",
        @JvmField var ackStatus: String = ""
    )

    data class AckMessageResponse(
        @JvmField var ok: Boolean = false,
        @JvmField var messageId: String = "",
        @JvmField var ackStatus: String = ""
    )
}
