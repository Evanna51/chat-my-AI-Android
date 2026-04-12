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
    var title: String? = null

    @JvmField
    var outline: String? = null

    @JvmField
    var avatar: String? = null

    @JvmField
    var category: String? = null

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
