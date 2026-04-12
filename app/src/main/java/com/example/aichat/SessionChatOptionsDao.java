package com.example.aichat;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface SessionChatOptionsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SessionChatOptionsEntity entity);

    @Query("SELECT * FROM session_chat_options WHERE sessionId = :sessionId")
    SessionChatOptionsEntity get(String sessionId);

    @Query("SELECT COUNT(*) FROM session_chat_options WHERE sessionId = :sessionId")
    int has(String sessionId);

    @Query("DELETE FROM session_chat_options WHERE sessionId = :sessionId")
    void delete(String sessionId);
}
