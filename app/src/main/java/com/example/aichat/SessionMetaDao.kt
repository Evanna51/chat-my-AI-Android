package com.example.aichat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SessionMetaEntity)

    @Query("SELECT * FROM session_meta WHERE sessionId = :sessionId")
    fun get(sessionId: String): SessionMetaEntity?

    @Query("SELECT * FROM session_meta")
    fun getAll(): List<SessionMetaEntity>

    @Query("DELETE FROM session_meta WHERE sessionId = :sessionId")
    fun delete(sessionId: String)

    @Query("UPDATE session_meta SET title = :title WHERE sessionId = :sessionId")
    fun updateTitle(sessionId: String, title: String)

    @Query("UPDATE session_meta SET category = :category WHERE sessionId = :sessionId")
    fun updateCategory(sessionId: String, category: String)

    @Query("UPDATE session_meta SET favorite = :value WHERE sessionId = :sessionId")
    fun setFavorite(sessionId: String, value: Boolean)

    @Query("UPDATE session_meta SET pinned = :value WHERE sessionId = :sessionId")
    fun setPinned(sessionId: String, value: Boolean)

    @Query("UPDATE session_meta SET deleted = :value WHERE sessionId = :sessionId")
    fun setDeleted(sessionId: String, value: Boolean)

    @Query("UPDATE session_meta SET hidden = :value WHERE sessionId = :sessionId")
    fun setHidden(sessionId: String, value: Boolean)

    @Query("UPDATE session_meta SET outline = :outline WHERE sessionId = :sessionId")
    fun updateOutline(sessionId: String, outline: String)

    @Query("SELECT DISTINCT category FROM session_meta WHERE category IS NOT NULL AND category != '' AND hidden = 0 AND deleted = 0")
    fun getAllCategories(): List<String>
}
