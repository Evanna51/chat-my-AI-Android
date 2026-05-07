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

    /**
     * 同 deleteBySession 但保留 tool_call (role=3), tool_result (role=4),
     * 以及自动对话产生的 split / follow-up 行 (proactiveKind != 0).
     *
     * 用于 persistSessionMessagesAsync 的 "snapshot replace user/assistant" 路径,
     * 让 Activity 同步 in-memory 列表时不再误删 tool 审计行 / 主动对话行.
     */
    @Query("DELETE FROM message WHERE sessionId = :sessionId AND role IN (0, 1) AND proactiveKind = 0")
    fun deleteUserAssistantBySession(sessionId: String)

    @Query("DELETE FROM message WHERE id = :id")
    fun deleteById(id: Long)

    @Query("SELECT * FROM message WHERE sessionId = :sessionId AND role IN (0, 1) AND proactiveKind = 0 ORDER BY createdAt ASC")
    fun getUserAssistantBySession(sessionId: String): List<Message>

    /**
     * 仅更新内容相关字段, 保留 sync 状态 (turnId / assistantId / synced / syncAttempts) 不变.
     * 用于 persistSessionMessagesAsync 增量 upsert 路径 — content/reasoning 改了, sync 队列状态不该被擦.
     */
    @Query(
        "UPDATE message SET content = :content, reasoning = :reasoning, " +
        "thinkingElapsedMs = :thinkingElapsedMs WHERE id = :id"
    )
    fun updateContentForSnapshot(id: Long, content: String, reasoning: String, thinkingElapsedMs: Long)

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

    /** 自动对话 split 改写: 把刚保存的 consolidated 消息切成 split[0]. */
    @Query("UPDATE message SET content = :content, proactiveKind = :kind WHERE id = :id")
    fun updateContentAndProactiveKind(id: Long, content: String, kind: Int)

    @Query("SELECT * FROM message WHERE embedding = '' AND content != '' LIMIT :limit")
    fun getMessagesNeedingEmbedding(limit: Int): List<Message>

    /** Substring search across user/assistant message content, newest first. */
    @Query(
        "SELECT * FROM message " +
        "WHERE role IN (0, 1) AND content LIKE :pattern " +
        "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    fun searchByKeyword(pattern: String, limit: Int): List<Message>

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

    // ─────────── History backfill ───────────

    /** 历史消息中尚未进入同步队列的行 (user/assistant 普通消息, 无 turnId, 非 proactive). */
    @Query(
        "SELECT * FROM message " +
        "WHERE turnId = '' AND role IN (0, 1) AND proactiveKind = 0 AND content != '' " +
        "ORDER BY createdAt ASC"
    )
    fun unstampedMessages(): List<Message>

    @Query(
        "UPDATE message SET turnId = :turnId, assistantId = :assistantId, synced = 0 " +
        "WHERE id = :id"
    )
    fun stampSyncFields(id: Long, turnId: String, assistantId: String)
}
