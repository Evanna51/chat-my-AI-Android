package com.example.aichat;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Message message);

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    List<Message> getBySession(String sessionId);

    @Query("DELETE FROM message WHERE sessionId = :sessionId")
    void deleteBySession(String sessionId);

    @Query("SELECT DISTINCT sessionId FROM message ORDER BY createdAt DESC")
    List<String> getAllSessionIds();

    @Query("SELECT m.sessionId as sessionId, " +
            "(SELECT content FROM message m2 WHERE m2.sessionId = m.sessionId AND m2.role = 0 ORDER BY m2.createdAt ASC LIMIT 1) as title, " +
            "MAX(m.createdAt) as lastAt " +
            "FROM message m GROUP BY m.sessionId ORDER BY lastAt DESC")
    List<SessionSummary> getRecentSessions();

    @Query("SELECT m.sessionId as sessionId, " +
            "(SELECT content FROM message m2 WHERE m2.sessionId = m.sessionId AND m2.role = 0 ORDER BY m2.createdAt ASC LIMIT 1) as title, " +
            "MAX(m.createdAt) as lastAt " +
            "FROM message m GROUP BY m.sessionId ORDER BY lastAt DESC")
    List<SessionSummary> getAllSessions();
}
