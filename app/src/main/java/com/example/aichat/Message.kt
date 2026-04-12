package com.example.aichat

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "message")
class Message {

    companion object {
        @JvmField val ROLE_USER = 0
        @JvmField val ROLE_ASSISTANT = 1
    }

    @PrimaryKey(autoGenerate = true)
    @JvmField
    var id: Long = 0

    @JvmField
    var sessionId: String = ""

    @JvmField
    var role: Int = 0

    @JvmField
    var content: String = ""

    @JvmField
    var createdAt: Long = 0

    @Ignore
    @JvmField
    var reasoning: String = ""

    @Ignore
    @JvmField
    var promptTokens: Int = 0

    @Ignore
    @JvmField
    var completionTokens: Int = 0

    @Ignore
    @JvmField
    var totalTokens: Int = 0

    @Ignore
    @JvmField
    var elapsedMs: Long = 0

    @Ignore
    @JvmField
    var thinkingRunning: Boolean = false

    @Ignore
    @JvmField
    var thinkingStartedAt: Long = 0

    @Ignore
    @JvmField
    var thinkingElapsedMs: Long = 0

    constructor()

    @Ignore
    constructor(sessionId: String, role: Int, content: String) {
        this.sessionId = sessionId
        this.role = role
        this.content = content
        this.createdAt = System.currentTimeMillis()
    }
}
