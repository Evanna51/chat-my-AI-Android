package com.example.aichat

data class SessionOutlineItem(
    @JvmField var id: String = "",
    @JvmField var type: String = "", // chapter / material / task / world
    @JvmField var title: String = "",
    @JvmField var content: String = "",
    @JvmField var createdAt: Long = 0L,
    @JvmField var updatedAt: Long = 0L
)
