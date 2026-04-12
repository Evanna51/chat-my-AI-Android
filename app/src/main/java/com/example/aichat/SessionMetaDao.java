package com.example.aichat;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SessionMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SessionMetaEntity entity);

    @Query("SELECT * FROM session_meta WHERE sessionId = :sessionId")
    SessionMetaEntity get(String sessionId);

    @Query("SELECT * FROM session_meta")
    List<SessionMetaEntity> getAll();

    @Query("DELETE FROM session_meta WHERE sessionId = :sessionId")
    void delete(String sessionId);

    @Query("UPDATE session_meta SET title = :title WHERE sessionId = :sessionId")
    void updateTitle(String sessionId, String title);

    @Query("UPDATE session_meta SET category = :category WHERE sessionId = :sessionId")
    void updateCategory(String sessionId, String category);

    @Query("UPDATE session_meta SET favorite = :value WHERE sessionId = :sessionId")
    void setFavorite(String sessionId, boolean value);

    @Query("UPDATE session_meta SET pinned = :value WHERE sessionId = :sessionId")
    void setPinned(String sessionId, boolean value);

    @Query("UPDATE session_meta SET deleted = :value WHERE sessionId = :sessionId")
    void setDeleted(String sessionId, boolean value);

    @Query("UPDATE session_meta SET hidden = :value WHERE sessionId = :sessionId")
    void setHidden(String sessionId, boolean value);

    @Query("UPDATE session_meta SET outline = :outline WHERE sessionId = :sessionId")
    void updateOutline(String sessionId, String outline);

    @Query("SELECT DISTINCT category FROM session_meta WHERE category IS NOT NULL AND category != '' AND hidden = 0 AND deleted = 0")
    List<String> getAllCategories();
}
