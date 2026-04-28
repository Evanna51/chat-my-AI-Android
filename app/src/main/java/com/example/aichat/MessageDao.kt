package com.example.aichat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(message: Message): Long

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getBySession(sessionId: String): List<Message>

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun getLatestBySession(sessionId: String, limit: Int): List<Message>

    @Query(
        "SELECT * FROM message WHERE sessionId = :sessionId " +
        "AND (createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND id < :beforeId)) " +
        "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    fun getOlderBySession(sessionId: String, beforeCreatedAt: Long, beforeId: Long, limit: Int): List<Message>

    @Query(
        "SELECT COUNT(*) FROM message WHERE sessionId = :sessionId " +
        "AND (createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND id < :beforeId))"
    )
    fun countOlderMessages(sessionId: String, beforeCreatedAt: Long, beforeId: Long): Int

    @Query("DELETE FROM message WHERE sessionId = :sessionId")
    fun deleteBySession(sessionId: String)

    @Query("SELECT COUNT(*) FROM message WHERE sessionId = :sessionId")
    fun countBySessionId(sessionId: String): Int

    @Query("SELECT DISTINCT sessionId FROM message ORDER BY createdAt DESC")
    fun getAllSessionIds(): List<String>

    @Query(
        "SELECT m.sessionId as sessionId, " +
        "(SELECT content FROM message m2 WHERE m2.sessionId = m.sessionId AND m2.role = 0 ORDER BY m2.createdAt ASC LIMIT 1) as title, " +
        "(SELECT content FROM message m3 WHERE m3.sessionId = m.sessionId ORDER BY m3.createdAt DESC, m3.id DESC LIMIT 1) as lastMessage, " +
        "MAX(m.createdAt) as lastAt " +
        "FROM message m GROUP BY m.sessionId ORDER BY lastAt DESC"
    )
    fun getRecentSessions(): List<SessionSummary>

    @Query(
        "SELECT m.sessionId as sessionId, " +
        "(SELECT content FROM message m2 WHERE m2.sessionId = m.sessionId AND m2.role = 0 ORDER BY m2.createdAt ASC LIMIT 1) as title, " +
        "(SELECT content FROM message m3 WHERE m3.sessionId = m.sessionId ORDER BY m3.createdAt DESC, m3.id DESC LIMIT 1) as lastMessage, " +
        "MAX(m.createdAt) as lastAt " +
        "FROM message m GROUP BY m.sessionId ORDER BY lastAt DESC"
    )
    fun getAllSessions(): List<SessionSummary>

    @Query("SELECT sessionId FROM message WHERE sessionId IN (:sessionIds) GROUP BY sessionId ORDER BY MAX(createdAt) DESC LIMIT 1")
    fun getLatestSessionIdIn(sessionIds: List<String>): String?

    @Query("UPDATE message SET embedding = :embedding WHERE id = :id")
    fun updateEmbedding(id: Long, embedding: String)

    @Query("SELECT * FROM message WHERE embedding = '' AND content != '' LIMIT :limit")
    fun getMessagesNeedingEmbedding(limit: Int): List<Message>

    @Query("SELECT * FROM message WHERE embedding != ''")
    fun getAllWithEmbedding(): List<Message>

    // ─────────── Remote sync queue ───────────

    @Query(
        "SELECT * FROM message " +
        "WHERE turnId != '' AND assistantId != '' AND synced = 0 AND syncAttempts < :maxAttempts " +
        "ORDER BY createdAt ASC LIMIT :limit"
    )
    fun pendingSyncBatch(maxAttempts: Int, limit: Int): List<Message>

    @Query(
        "UPDATE message SET synced = 1, lastError = '' " +
        "WHERE turnId IN (:turnIds)"
    )
    fun markSynced(turnIds: List<String>)

    @Query(
        "UPDATE message SET syncAttempts = syncAttempts + 1, " +
        "lastAttemptAt = :ts, lastError = :err " +
        "WHERE turnId IN (:turnIds)"
    )
    fun markSyncFailed(turnIds: List<String>, ts: Long, err: String)

    @Query("SELECT COUNT(*) FROM message WHERE turnId != '' AND synced = 0")
    fun pendingSyncCount(): Int

    @Query("SELECT COUNT(*) FROM message WHERE turnId != '' AND synced = 1 AND assistantId = :assistantId")
    fun syncedCountForAssistant(assistantId: String): Int
}
