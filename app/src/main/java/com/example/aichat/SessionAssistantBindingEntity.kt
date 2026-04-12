package com.example.aichat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_assistant_binding")
class SessionAssistantBindingEntity {

    @PrimaryKey
    @JvmField
    var sessionId: String = ""

    @ColumnInfo(index = true)
    @JvmField
    var assistantId: String? = null
}
