package com.example.aichat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "my_assistant")
class MyAssistantEntity {

    @PrimaryKey
    @JvmField
    var id: String = ""

    @JvmField
    var name: String? = null

    /** @deprecated Legacy field; use options_json → systemPrompt instead. Kept for migration. */
    @Deprecated("Legacy field; use options_json → systemPrompt instead. Kept for migration.")
    @JvmField
    var prompt: String? = null

    @JvmField
    var avatar: String? = null

    @JvmField
    var avatarImageBase64: String? = null

    @JvmField
    var firstDialogue: String? = null

    @JvmField
    var type: String? = null

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var allowAutoLife: Boolean = false

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var allowProactiveMessage: Boolean = false

    /** Gson-serialized SessionChatOptions JSON string. */
    @ColumnInfo(name = "options_json")
    @JvmField
    var optionsJson: String? = null

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var updatedAt: Long = 0
}
