package com.example.aichat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionAssistantBindingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SessionAssistantBindingEntity)

    @Query("SELECT assistantId FROM session_assistant_binding WHERE sessionId = :sessionId")
    fun getAssistantId(sessionId: String): String?

    @Query("SELECT COUNT(*) FROM session_assistant_binding WHERE sessionId = :sessionId")
    fun contains(sessionId: String): Int

    @Query("SELECT sessionId FROM session_assistant_binding WHERE assistantId = :assistantId ORDER BY sessionId ASC")
    fun getSessionIdsByAssistantId(assistantId: String): List<String>

    @Query("DELETE FROM session_assistant_binding WHERE sessionId = :sessionId")
    fun delete(sessionId: String)
}
