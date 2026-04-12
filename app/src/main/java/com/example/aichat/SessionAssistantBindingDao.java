package com.example.aichat;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SessionAssistantBindingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SessionAssistantBindingEntity entity);

    @Query("SELECT assistantId FROM session_assistant_binding WHERE sessionId = :sessionId")
    String getAssistantId(String sessionId);

    @Query("SELECT COUNT(*) FROM session_assistant_binding WHERE sessionId = :sessionId")
    int contains(String sessionId);

    @Query("SELECT sessionId FROM session_assistant_binding WHERE assistantId = :assistantId ORDER BY sessionId ASC")
    List<String> getSessionIdsByAssistantId(String assistantId);

    @Query("DELETE FROM session_assistant_binding WHERE sessionId = :sessionId")
    void delete(String sessionId);
}
