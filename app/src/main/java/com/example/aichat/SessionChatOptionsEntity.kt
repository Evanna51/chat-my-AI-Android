package com.example.aichat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_chat_options")
class SessionChatOptionsEntity {

    @PrimaryKey
    @JvmField
    var sessionId: String = ""

    @JvmField
    var sessionTitle: String? = null

    @JvmField
    var sessionAvatar: String? = null

    @JvmField
    var modelKey: String? = null

    @JvmField
    var systemPrompt: String? = null

    @JvmField
    var stop: String? = null

    @ColumnInfo(defaultValue = "6")
    @JvmField
    var contextMessageCount: Int = 6

    @ColumnInfo(defaultValue = "1024")
    @JvmField
    var googleThinkingBudget: Int = 1024

    @ColumnInfo(defaultValue = "0.7")
    @JvmField
    var temperature: Float = 0.7f

    @ColumnInfo(defaultValue = "1.0")
    @JvmField
    var topP: Float = 1.0f

    @ColumnInfo(defaultValue = "1")
    @JvmField
    var streamOutput: Boolean = true

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var autoChapterPlan: Boolean = false

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var thinking: Boolean = false
}
