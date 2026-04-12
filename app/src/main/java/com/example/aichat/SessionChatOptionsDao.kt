package com.example.aichat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionChatOptionsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SessionChatOptionsEntity)

    @Query("SELECT * FROM session_chat_options WHERE sessionId = :sessionId")
    fun get(sessionId: String): SessionChatOptionsEntity?

    @Query("SELECT COUNT(*) FROM session_chat_options WHERE sessionId = :sessionId")
    fun has(sessionId: String): Int

    @Query("DELETE FROM session_chat_options WHERE sessionId = :sessionId")
    fun delete(sessionId: String)
}
