package com.example.aichat

data class MyAssistant(
    @JvmField var id: String = "",
    @JvmField var name: String = "",
    @JvmField var prompt: String = "",
    @JvmField var avatar: String = "",
    @JvmField var avatarImageBase64: String = "",
    @JvmField var firstDialogue: String = "",
    @JvmField var type: String = "", // default / writer
    @JvmField var allowAutoLife: Boolean = false,
    @JvmField var allowProactiveMessage: Boolean = false,
    @JvmField var options: SessionChatOptions? = null,
    @JvmField var updatedAt: Long = 0L
)
