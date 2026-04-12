package com.example.aichat

data class SessionChatOptions(
    @JvmField var sessionTitle: String = "",
    @JvmField var sessionAvatar: String = "",
    @JvmField var contextMessageCount: Int = 6,
    @JvmField var modelKey: String = "",
    @JvmField var systemPrompt: String = "",
    @JvmField var temperature: Float = 0.7f,
    @JvmField var topP: Float = 1.0f,
    @JvmField var stop: String = "",
    @JvmField var streamOutput: Boolean = true,
    @JvmField var autoChapterPlan: Boolean = false,
    @JvmField var thinking: Boolean = false,
    @JvmField var googleThinkingBudget: Int = 1024
)
