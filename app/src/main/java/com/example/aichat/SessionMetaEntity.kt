package com.example.aichat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_meta")
class SessionMetaEntity {

    @PrimaryKey
    @JvmField
    var sessionId: String = ""

    @JvmField
    var title: String = ""

    @JvmField
    var outline: String = ""

    @JvmField
    var avatar: String = ""

    @JvmField
    var category: String = "默认"

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var favorite: Boolean = false

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var pinned: Boolean = false

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var hidden: Boolean = false

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var deleted: Boolean = false
}
